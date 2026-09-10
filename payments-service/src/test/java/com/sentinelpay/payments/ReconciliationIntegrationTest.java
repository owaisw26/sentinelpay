package com.sentinelpay.payments;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;

import com.sentinelpay.payments.domain.OutboxEvent;
import com.sentinelpay.payments.domain.Payment;
import com.sentinelpay.payments.domain.PaymentReservationStatus;
import com.sentinelpay.payments.domain.PaymentStatus;
import com.sentinelpay.payments.domain.ReconciliationAction;
import com.sentinelpay.payments.domain.ReconciliationDiscrepancy;
import com.sentinelpay.payments.domain.ReconciliationDiscrepancyStatus;
import com.sentinelpay.payments.domain.ReconciliationDiscrepancyType;
import com.sentinelpay.payments.domain.ReconciliationRun;
import com.sentinelpay.payments.domain.ReconciliationRunStatus;
import com.sentinelpay.payments.domain.User;
import com.sentinelpay.payments.domain.Wallet;
import com.sentinelpay.payments.provider.PaymentProvider;
import com.sentinelpay.payments.provider.PaymentProviderLookupResult;
import com.sentinelpay.payments.provider.PaymentProviderLookupStatus;
import com.sentinelpay.payments.provider.PaymentProviderResponse;
import com.sentinelpay.payments.provider.PaymentProviderStatus;
import com.sentinelpay.payments.repository.LedgerTransactionRepository;
import com.sentinelpay.payments.repository.OutboxEventRepository;
import com.sentinelpay.payments.repository.PaymentRepository;
import com.sentinelpay.payments.repository.PaymentReservationRepository;
import com.sentinelpay.payments.repository.ReconciliationAuditRepository;
import com.sentinelpay.payments.repository.ReconciliationDiscrepancyRepository;
import com.sentinelpay.payments.repository.WalletRepository;
import com.sentinelpay.payments.service.PaymentService;
import com.sentinelpay.payments.service.UserService;
import com.sentinelpay.payments.service.WalletService;
import com.sentinelpay.payments.service.outbox.OutboxMessage;
import com.sentinelpay.payments.service.outbox.PaymentEventProcessor;
import com.sentinelpay.payments.service.reconciliation.ReconciliationDetector;
import com.sentinelpay.payments.service.reconciliation.ReconciliationResolutionService;

import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;

@SpringBootTest(
    properties = {
        "sentinelpay.reconciliation.stale-after-seconds=300",
        "sentinelpay.reconciliation.batch-size=1"
    },
    classes = {
        PaymentsServiceApplication.class,
        ReconciliationIntegrationTest.ProviderConfiguration.class
    }
)
@Transactional
class ReconciliationIntegrationTest extends AbstractIntegrationTest {
    @Autowired private UserService userService;
    @Autowired private WalletService walletService;
    @Autowired private PaymentService paymentService;
    @Autowired private PaymentEventProcessor paymentEventProcessor;
    @Autowired private ReconciliationDetector reconciliationDetector;
    @Autowired private ReconciliationResolutionService resolutionService;
    @Autowired private TestPaymentProvider provider;
    @Autowired private OutboxEventRepository outboxEventRepository;
    @Autowired private PaymentRepository paymentRepository;
    @Autowired private PaymentReservationRepository reservationRepository;
    @Autowired private WalletRepository walletRepository;
    @Autowired private LedgerTransactionRepository ledgerTransactionRepository;
    @Autowired private ReconciliationDiscrepancyRepository discrepancyRepository;
    @Autowired private ReconciliationAuditRepository auditRepository;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private EntityManager entityManager;

    @Test
    void detectsAndManuallyResolvesMissingSuccessAndDecline() {
        User senderUser = userService.createUser("Reconciliation Sender", "CUSTOMER");
        User receiverUser = userService.createUser("Reconciliation Receiver", "CUSTOMER");
        Wallet sender = walletService.createWallet(senderUser.getUserId(), "AUD");
        Wallet receiver = walletService.createWallet(receiverUser.getUserId(), "AUD");
        sender.setBalance(new BigDecimal("100.00"));
        walletRepository.saveAndFlush(sender);

        Payment success = createProcessingPayment(
            senderUser, sender, receiver, "missing-success",
            PaymentProviderLookupStatus.SUCCEEDED
        );
        Payment decline = createProcessingPayment(
            senderUser, sender, receiver, "missing-decline",
            PaymentProviderLookupStatus.DECLINED
        );
        jdbcTemplate.update(
            "update payments set updated_at = updated_at - interval '10 minutes' "
                + "where id in (?, ?)",
            success.getId(),
            decline.getId()
        );
        entityManager.flush();
        entityManager.clear();

        ReconciliationRun run = reconciliationDetector.run("analyst-1");

        assertEquals(ReconciliationRunStatus.COMPLETED, run.getStatus());
        assertEquals(2, run.getScannedCount());
        assertEquals(2, run.getDiscrepancyCount());

        ReconciliationDiscrepancy successDiscrepancy = open(success.getId());
        ReconciliationDiscrepancy declineDiscrepancy = open(decline.getId());
        assertEquals(
            ReconciliationDiscrepancyType.MISSING_SUCCESS,
            successDiscrepancy.getType()
        );
        assertEquals(ReconciliationAction.CAPTURE,
            successDiscrepancy.getRecommendedAction());
        assertEquals(
            ReconciliationDiscrepancyType.MISSING_DECLINE,
            declineDiscrepancy.getType()
        );
        assertEquals(ReconciliationAction.RELEASE,
            declineDiscrepancy.getRecommendedAction());

        String captureKey = UUID.randomUUID().toString();
        ReconciliationDiscrepancy captured = resolutionService.resolve(
            successDiscrepancy.getId(),
            successDiscrepancy.getVersion(),
            ReconciliationAction.CAPTURE,
            "PSP confirms final success",
            captureKey,
            "analyst-1"
        );
        ReconciliationDiscrepancy replay = resolutionService.resolve(
            successDiscrepancy.getId(),
            successDiscrepancy.getVersion(),
            ReconciliationAction.CAPTURE,
            "PSP confirms final success",
            captureKey,
            "analyst-1"
        );
        assertSame(captured, replay);

        resolutionService.resolve(
            declineDiscrepancy.getId(),
            declineDiscrepancy.getVersion(),
            ReconciliationAction.RELEASE,
            "PSP confirms final decline",
            UUID.randomUUID().toString(),
            "analyst-1"
        );
        entityManager.flush();
        entityManager.clear();

        Payment settled = paymentRepository.findById(success.getId()).orElseThrow();
        Payment failed = paymentRepository.findById(decline.getId()).orElseThrow();
        Wallet finalSender = walletRepository.findById(sender.getId()).orElseThrow();
        Wallet finalReceiver = walletRepository.findById(receiver.getId()).orElseThrow();

        assertEquals(PaymentStatus.SETTLED, settled.getStatus());
        assertEquals(PaymentStatus.FAILED, failed.getStatus());
        assertEquals(0, new BigDecimal("75.00").compareTo(finalSender.getBalance()));
        assertEquals(0, BigDecimal.ZERO.compareTo(finalSender.getReservedBalance()));
        assertEquals(0, new BigDecimal("25.00").compareTo(finalReceiver.getBalance()));
        assertEquals(PaymentReservationStatus.CAPTURED,
            reservationRepository.findById(success.getId()).orElseThrow().getStatus());
        assertEquals(PaymentReservationStatus.RELEASED,
            reservationRepository.findById(decline.getId()).orElseThrow().getStatus());
        assertEquals(1, ledgerTransactionRepository.findAll().stream()
            .filter(transaction -> success.getId().equals(transaction.getPaymentId()))
            .count());
        assertEquals(ReconciliationDiscrepancyStatus.RESOLVED,
            discrepancyRepository.findById(successDiscrepancy.getId())
                .orElseThrow().getStatus());
        assertTrue(auditRepository
            .findByDiscrepancyIdOrderByOccurredAtAscIdAsc(
                successDiscrepancy.getId()
            ).stream()
            .anyMatch(audit -> "RESOLUTION_CAPTURE".equals(audit.getAction())));
        assertTrue(auditRepository
            .findByDiscrepancyIdOrderByOccurredAtAscIdAsc(
                declineDiscrepancy.getId()
            ).stream()
            .anyMatch(audit -> "RESOLUTION_RELEASE".equals(audit.getAction())));
    }

    @Test
    void ignoreCanCloseLegacyPaymentWithoutReservation() {
        User senderUser = userService.createUser(
            "Legacy Reconciliation Sender", "CUSTOMER"
        );
        User receiverUser = userService.createUser(
            "Legacy Reconciliation Receiver", "CUSTOMER"
        );
        Wallet sender = walletService.createWallet(senderUser.getUserId(), "AUD");
        Wallet receiver = walletService.createWallet(receiverUser.getUserId(), "AUD");
        sender.setBalance(new BigDecimal("100.00"));
        walletRepository.saveAndFlush(sender);

        Payment payment = createProcessingPayment(
            senderUser, sender, receiver, "legacy-missing-provider",
            PaymentProviderLookupStatus.NOT_FOUND
        );
        entityManager.flush();
        jdbcTemplate.update(
            "delete from payment_reservations where payment_id = ?",
            payment.getId()
        );
        jdbcTemplate.update(
            "update wallets set reserved_balance = 0 where id = ?",
            sender.getId()
        );
        jdbcTemplate.update(
            "update payments set updated_at = updated_at - interval '10 minutes' "
                + "where id = ?",
            payment.getId()
        );
        entityManager.clear();

        reconciliationDetector.run("analyst-legacy");
        ReconciliationDiscrepancy discrepancy = open(payment.getId());
        ReconciliationDiscrepancy resolved = resolutionService.resolve(
            discrepancy.getId(),
            discrepancy.getVersion(),
            ReconciliationAction.IGNORE,
            "Close pre-reservation local demo artifact",
            UUID.randomUUID().toString(),
            "analyst-legacy"
        );

        assertEquals(ReconciliationDiscrepancyStatus.IGNORED,
            resolved.getStatus());
        assertEquals(PaymentStatus.PROCESSING,
            paymentRepository.findById(payment.getId()).orElseThrow().getStatus());
    }

    private Payment createProcessingPayment(
        User senderUser,
        Wallet sender,
        Wallet receiver,
        String reference,
        PaymentProviderLookupStatus finalStatus
    ) {
        Payment payment = paymentService.createPayment(
            senderUser.getUserId(),
            sender.getId(),
            receiver.getId(),
            new BigDecimal("25.00"),
            "AUD",
            reference + "-" + UUID.randomUUID(),
            UUID.randomUUID()
        );
        provider.setStatus(payment.getId(), finalStatus);
        OutboxEvent event = outboxEventRepository
            .findOutboxEventsByAggregateId(payment.getId())
            .getFirst();
        paymentEventProcessor.process(new OutboxMessage(
            event.getId(), event.getAggregateId(), event.getEventType(),
            event.getCorrelationId(), event.getCreatedAt(), event.getPayload()
        ));
        assertEquals(PaymentStatus.PROCESSING, payment.getStatus());
        return payment;
    }

    private ReconciliationDiscrepancy open(UUID paymentId) {
        return discrepancyRepository.findByPaymentIdAndStatus(
            paymentId, ReconciliationDiscrepancyStatus.OPEN
        ).orElseThrow();
    }

    @TestConfiguration
    static class ProviderConfiguration {
        @Bean
        @Primary
        TestPaymentProvider testPaymentProvider() {
            return new TestPaymentProvider();
        }
    }

    static class TestPaymentProvider implements PaymentProvider {
        private final Map<UUID, PaymentProviderLookupStatus> statuses =
            new ConcurrentHashMap<>();

        void setStatus(UUID paymentId, PaymentProviderLookupStatus status) {
            statuses.put(paymentId, status);
        }

        @Override
        public PaymentProviderResponse processPayment(
            Payment payment,
            UUID providerIdempotencyKey
        ) {
            assertEquals(payment.getId(), providerIdempotencyKey);
            return new PaymentProviderResponse(
                "provider-" + payment.getId(),
                PaymentProviderStatus.ACCEPTED
            );
        }

        @Override
        public PaymentProviderLookupResult lookupPayment(UUID idempotencyKey) {
            PaymentProviderLookupStatus status = statuses.getOrDefault(
                idempotencyKey, PaymentProviderLookupStatus.NOT_FOUND
            );
            return new PaymentProviderLookupResult(
                status == PaymentProviderLookupStatus.NOT_FOUND
                    ? null
                    : "provider-" + idempotencyKey,
                status
            );
        }
    }
}
