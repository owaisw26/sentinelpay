package com.sentinelpay.payments;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import com.sentinelpay.payments.domain.LedgerEntry;
import com.sentinelpay.payments.domain.LedgerTransaction;
import com.sentinelpay.payments.domain.OutboxEvent;
import com.sentinelpay.payments.domain.Payment;
import com.sentinelpay.payments.domain.PaymentStatus;
import com.sentinelpay.payments.domain.PaymentReservationStatus;
import com.sentinelpay.payments.domain.ReconciliationDiscrepancyStatus;
import com.sentinelpay.payments.domain.ReconciliationDiscrepancyType;
import com.sentinelpay.payments.domain.User;
import com.sentinelpay.payments.domain.Wallet;
import com.sentinelpay.payments.provider.PaymentProviderWebhook;
import com.sentinelpay.payments.provider.PaymentProviderWebhookStatus;
import com.sentinelpay.payments.provider.FakePaymentProvider;
import com.sentinelpay.payments.repository.LedgerEntryRepository;
import com.sentinelpay.payments.repository.LedgerTransactionRepository;
import com.sentinelpay.payments.repository.OutboxEventRepository;
import com.sentinelpay.payments.repository.PaymentRepository;
import com.sentinelpay.payments.repository.PaymentReservationRepository;
import com.sentinelpay.payments.repository.ReconciliationDiscrepancyRepository;
import com.sentinelpay.payments.repository.WalletRepository;
import com.sentinelpay.payments.repository.WebhookReceiptRepository;
import com.sentinelpay.payments.service.PaymentService;
import com.sentinelpay.payments.service.PaymentWebhookProcessor;
import com.sentinelpay.payments.service.UserService;
import com.sentinelpay.payments.service.WalletService;
import com.sentinelpay.payments.service.outbox.OutboxMessage;
import com.sentinelpay.payments.service.outbox.PaymentEventProcessor;
import com.sentinelpay.payments.service.outbox.PaymentProcessingService;

import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;

@SpringBootTest(properties = "sentinelpay.psp.mode=SUCCESS")
@Transactional
class PaymentWebhookIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private UserService userService;

    @Autowired
    private WalletService walletService;

    @Autowired
    private PaymentService paymentService;

    @Autowired
    private PaymentEventProcessor paymentEventProcessor;

    @Autowired
    private PaymentProcessingService paymentProcessingService;

    @Autowired
    private PaymentWebhookProcessor paymentWebhookProcessor;

    @Autowired
    private OutboxEventRepository outboxEventRepository;

    @Autowired
    private PaymentRepository paymentRepository;

    @Autowired
    private PaymentReservationRepository paymentReservationRepository;

    @Autowired
    private WalletRepository walletRepository;

    @Autowired
    private LedgerTransactionRepository ledgerTransactionRepository;

    @Autowired
    private LedgerEntryRepository ledgerEntryRepository;

    @Autowired
    private WebhookReceiptRepository webhookReceiptRepository;

    @Autowired
    private ReconciliationDiscrepancyRepository discrepancyRepository;

    @Autowired
    private EntityManager entityManager;

    @Test
    void successfulDuplicateWebhookSettlesAndTransfersExactlyOnce() {
        User senderUser = userService.createUser("Webhook Sender", "CUSTOMER");
        User receiverUser = userService.createUser("Webhook Receiver", "CUSTOMER");
        Wallet sender = walletService.createWallet(senderUser.getUserId(), "AUD");
        Wallet receiver = walletService.createWallet(receiverUser.getUserId(), "AUD");
        sender.setBalance(new BigDecimal("100.00"));
        walletRepository.saveAndFlush(sender);

        BigDecimal amount = new BigDecimal("25.00");
        String reference = "webhook-settlement-" + UUID.randomUUID();
        Payment payment = paymentService.createPayment(
            senderUser.getUserId(),
            sender.getId(),
            receiver.getId(),
            amount,
            "AUD",
            reference,
            UUID.randomUUID()
        );

        OutboxEvent paymentCreated = outboxEventRepository
            .findOutboxEventsByAggregateId(payment.getId())
            .getFirst();

        paymentEventProcessor.process(new OutboxMessage(
            paymentCreated.getId(),
            paymentCreated.getAggregateId(),
            paymentCreated.getEventType(),
            paymentCreated.getCorrelationId(),
            paymentCreated.getCreatedAt(),
            paymentCreated.getPayload()
        ));

        assertEquals(PaymentStatus.PROCESSING, payment.getStatus());
        assertNotNull(payment.getProviderPaymentId());
        assertEquals(
            0,
            amount.compareTo(sender.getReservedBalance())
        );

        PaymentProviderWebhook webhook = new PaymentProviderWebhook(
            UUID.randomUUID(),
            payment.getProviderPaymentId(),
            PaymentProviderWebhookStatus.SUCCEEDED
        );

        paymentWebhookProcessor.process(webhook);
        paymentWebhookProcessor.process(webhook);
        paymentWebhookProcessor.process(new PaymentProviderWebhook(
            UUID.randomUUID(),
            payment.getProviderPaymentId(),
            PaymentProviderWebhookStatus.SUCCEEDED
        ));

        entityManager.flush();
        entityManager.clear();

        Payment settledPayment = paymentRepository.findById(payment.getId())
            .orElseThrow();
        Wallet updatedSender = walletRepository.findById(sender.getId())
            .orElseThrow();
        Wallet updatedReceiver = walletRepository.findById(receiver.getId())
            .orElseThrow();
        LedgerTransaction ledgerTransaction = ledgerTransactionRepository
            .findTransactionByReference(reference)
            .orElseThrow();
        List<LedgerEntry> entries = ledgerEntryRepository
            .findAllByLedgerTransactionId(ledgerTransaction.getId());

        assertEquals(PaymentStatus.SETTLED, settledPayment.getStatus());
        assertEquals(
            0,
            new BigDecimal("75.00").compareTo(updatedSender.getBalance())
        );
        assertEquals(0, BigDecimal.ZERO.compareTo(updatedSender.getReservedBalance()));
        assertEquals(
            0,
            new BigDecimal("25.00").compareTo(updatedReceiver.getBalance())
        );
        assertEquals(2, entries.size());
        assertEquals(
            0,
            entries.stream()
                .map(LedgerEntry::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .compareTo(BigDecimal.ZERO)
        );
        assertEquals(
            1,
            entries.stream()
                .filter(entry -> entry.getWallet().getId().equals(sender.getId()))
                .filter(entry -> entry.getAmount().compareTo(amount.negate()) == 0)
                .count()
        );
        assertEquals(2, webhookReceiptRepository.findAll().stream()
            .filter(receipt -> receipt.getProviderPaymentId().equals(
                payment.getProviderPaymentId()))
            .peek(receipt -> assertNotNull(receipt.getProcessedAt()))
            .count());
        assertEquals(
            1,
            entries.stream()
                .filter(entry -> entry.getWallet().getId().equals(receiver.getId()))
                .filter(entry -> entry.getAmount().compareTo(amount) == 0)
                .count()
        );
    }

    @Test
    void declineModeFailsThroughWebhookWithoutMovingLedgerFunds() {
        User senderUser = userService.createUser("Decline Sender", "CUSTOMER");
        User receiverUser = userService.createUser("Decline Receiver", "CUSTOMER");
        Wallet sender = walletService.createWallet(senderUser.getUserId(), "AUD");
        Wallet receiver = walletService.createWallet(receiverUser.getUserId(), "AUD");
        sender.setBalance(new BigDecimal("100.00"));
        walletRepository.saveAndFlush(sender);

        String reference = "declined-payment-" + UUID.randomUUID();
        Payment payment = paymentService.createPayment(
            senderUser.getUserId(),
            sender.getId(),
            receiver.getId(),
            new BigDecimal("25.00"),
            "AUD",
            reference,
            UUID.randomUUID()
        );
        OutboxEvent event = outboxEventRepository
            .findOutboxEventsByAggregateId(payment.getId())
            .getFirst();
        PaymentEventProcessor declineProcessor = new PaymentEventProcessor(
            paymentProcessingService,
            new FakePaymentProvider("DECLINE", paymentWebhookProcessor)
        );

        declineProcessor.process(toMessage(event));
        entityManager.flush();
        entityManager.clear();

        Payment failedPayment = paymentRepository.findById(payment.getId())
            .orElseThrow();
        paymentWebhookProcessor.process(new PaymentProviderWebhook(
            UUID.randomUUID(),
            failedPayment.getProviderPaymentId(),
            PaymentProviderWebhookStatus.DECLINED
        ));
        Wallet unchangedSender = walletRepository.findById(sender.getId())
            .orElseThrow();
        Wallet unchangedReceiver = walletRepository.findById(receiver.getId())
            .orElseThrow();

        assertEquals(PaymentStatus.FAILED, failedPayment.getStatus());
        assertEquals(0, new BigDecimal("100.00").compareTo(unchangedSender.getBalance()));
        assertEquals(0, BigDecimal.ZERO.compareTo(unchangedSender.getReservedBalance()));
        assertEquals(0, BigDecimal.ZERO.compareTo(unchangedReceiver.getBalance()));
        assertTrue(ledgerTransactionRepository.findTransactionByReference(reference).isEmpty());
        assertEquals(PaymentReservationStatus.RELEASED,
            paymentReservationRepository.findById(payment.getId())
                .orElseThrow().getStatus());
        assertEquals(2, webhookReceiptRepository.findAll().stream()
            .filter(receipt -> receipt.getProviderPaymentId().equals(
                failedPayment.getProviderPaymentId()))
            .count());
    }

    @Test
    void duplicateWebhookModeEmitsSameSuccessTwiceAndSettlesOnce() {
        User senderUser = userService.createUser("Duplicate Sender", "CUSTOMER");
        User receiverUser = userService.createUser("Duplicate Receiver", "CUSTOMER");
        Wallet sender = walletService.createWallet(senderUser.getUserId(), "AUD");
        Wallet receiver = walletService.createWallet(receiverUser.getUserId(), "AUD");
        sender.setBalance(new BigDecimal("100.00"));
        walletRepository.saveAndFlush(sender);

        BigDecimal amount = new BigDecimal("25.00");
        String reference = "duplicate-payment-" + UUID.randomUUID();
        Payment payment = paymentService.createPayment(
            senderUser.getUserId(),
            sender.getId(),
            receiver.getId(),
            amount,
            "AUD",
            reference,
            UUID.randomUUID()
        );
        OutboxEvent event = outboxEventRepository
            .findOutboxEventsByAggregateId(payment.getId())
            .getFirst();
        PaymentEventProcessor duplicateProcessor = new PaymentEventProcessor(
            paymentProcessingService,
            new FakePaymentProvider("DUPLICATE_WEBHOOK", paymentWebhookProcessor)
        );

        duplicateProcessor.process(toMessage(event));
        entityManager.flush();
        entityManager.clear();

        Payment settledPayment = paymentRepository.findById(payment.getId())
            .orElseThrow();
        Wallet updatedSender = walletRepository.findById(sender.getId())
            .orElseThrow();
        Wallet updatedReceiver = walletRepository.findById(receiver.getId())
            .orElseThrow();

        assertEquals(PaymentStatus.SETTLED, settledPayment.getStatus());
        assertEquals(0, new BigDecimal("75.00").compareTo(updatedSender.getBalance()));
        assertEquals(0, new BigDecimal("25.00").compareTo(updatedReceiver.getBalance()));
        LedgerTransaction ledgerTransaction = ledgerTransactionRepository
            .findTransactionByReference(reference)
            .orElseThrow();
        List<LedgerEntry> entries = ledgerEntryRepository
            .findAllByLedgerTransactionId(ledgerTransaction.getId());
        assertEquals(2, entries.size());
        assertEquals(2, webhookReceiptRepository.findAll().stream()
            .filter(receipt -> receipt.getProviderPaymentId().equals(
                settledPayment.getProviderPaymentId()))
            .findFirst()
            .orElseThrow()
            .getDeliveryCount());
        assertEquals(
            0,
            entries.stream()
                .map(LedgerEntry::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .compareTo(BigDecimal.ZERO)
        );
    }

    @Test
    void autoSuccessModeEmitsOneSuccessWebhookAndSettles() {
        User senderUser = userService.createUser("Auto Sender", "CUSTOMER");
        User receiverUser = userService.createUser("Auto Receiver", "CUSTOMER");
        Wallet sender = walletService.createWallet(senderUser.getUserId(), "AUD");
        Wallet receiver = walletService.createWallet(receiverUser.getUserId(), "AUD");
        sender.setBalance(new BigDecimal("100.00"));
        walletRepository.saveAndFlush(sender);

        BigDecimal amount = new BigDecimal("25.00");
        Payment payment = paymentService.createPayment(
            senderUser.getUserId(), sender.getId(), receiver.getId(), amount,
            "AUD", "auto-success-" + UUID.randomUUID(), UUID.randomUUID()
        );
        OutboxEvent event = outboxEventRepository
            .findOutboxEventsByAggregateId(payment.getId()).getFirst();
        PaymentEventProcessor processor = new PaymentEventProcessor(
            paymentProcessingService,
            new FakePaymentProvider("AUTO_SUCCESS", paymentWebhookProcessor)
        );

        processor.process(toMessage(event));
        entityManager.flush();
        entityManager.clear();

        Payment settled = paymentRepository.findById(payment.getId()).orElseThrow();
        Wallet updatedSender = walletRepository.findById(sender.getId()).orElseThrow();
        Wallet updatedReceiver = walletRepository.findById(receiver.getId()).orElseThrow();

        assertEquals(PaymentStatus.SETTLED, settled.getStatus());
        assertEquals(0, new BigDecimal("75.00").compareTo(updatedSender.getBalance()));
        assertEquals(0, amount.compareTo(updatedReceiver.getBalance()));
        assertEquals(1, webhookReceiptRepository.findAll().stream()
            .filter(receipt -> receipt.getProviderPaymentId().equals(
                settled.getProviderPaymentId()))
            .findFirst()
            .orElseThrow()
            .getDeliveryCount());
    }

    @Test
    void contradictoryTerminalWebhookCreatesDiscrepancyWithoutReapplyingMoney() {
        User senderUser = userService.createUser("Contradiction Sender", "CUSTOMER");
        User receiverUser = userService.createUser("Contradiction Receiver", "CUSTOMER");
        Wallet sender = walletService.createWallet(senderUser.getUserId(), "AUD");
        Wallet receiver = walletService.createWallet(receiverUser.getUserId(), "AUD");
        sender.setBalance(new BigDecimal("100.00"));
        walletRepository.saveAndFlush(sender);

        Payment payment = paymentService.createPayment(
            senderUser.getUserId(), sender.getId(), receiver.getId(),
            new BigDecimal("25.00"), "AUD",
            "contradictory-" + UUID.randomUUID(), UUID.randomUUID()
        );
        OutboxEvent event = outboxEventRepository
            .findOutboxEventsByAggregateId(payment.getId()).getFirst();
        PaymentEventProcessor processor = new PaymentEventProcessor(
            paymentProcessingService,
            new FakePaymentProvider(
                "CONTRADICTORY_STATUS", paymentWebhookProcessor
            )
        );

        processor.process(toMessage(event));
        entityManager.flush();
        entityManager.clear();

        Payment settled = paymentRepository.findById(payment.getId()).orElseThrow();
        Wallet updatedSender = walletRepository.findById(sender.getId()).orElseThrow();
        Wallet updatedReceiver = walletRepository.findById(receiver.getId()).orElseThrow();
        assertEquals(PaymentStatus.SETTLED, settled.getStatus());
        assertEquals(0, new BigDecimal("75.00").compareTo(updatedSender.getBalance()));
        assertEquals(0, new BigDecimal("25.00").compareTo(updatedReceiver.getBalance()));
        assertEquals(1, ledgerTransactionRepository.findAll().stream()
            .filter(transaction -> payment.getId().equals(transaction.getPaymentId()))
            .count());
        assertEquals(ReconciliationDiscrepancyType.CONTRADICTORY_PROVIDER_STATUS,
            discrepancyRepository.findByPaymentIdAndStatus(
                payment.getId(), ReconciliationDiscrepancyStatus.OPEN
            ).orElseThrow().getType());
    }

    private OutboxMessage toMessage(OutboxEvent event) {
        return new OutboxMessage(
            event.getId(),
            event.getAggregateId(),
            event.getEventType(),
            event.getCorrelationId(),
            event.getCreatedAt(),
            event.getPayload()
        );
    }
}
