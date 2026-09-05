package com.sentinelpay.payments;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.fail;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.sentinelpay.payments.domain.OutboxEvent;
import com.sentinelpay.payments.domain.Payment;
import com.sentinelpay.payments.domain.PaymentStatus;
import com.sentinelpay.payments.domain.User;
import com.sentinelpay.payments.domain.Wallet;
import com.sentinelpay.payments.repository.OutboxEventRepository;
import com.sentinelpay.payments.repository.PaymentRepository;
import com.sentinelpay.payments.repository.PaymentReservationRepository;
import com.sentinelpay.payments.repository.ProcessedEventRepository;
import com.sentinelpay.payments.repository.ProviderAttemptRepository;
import com.sentinelpay.payments.repository.UserRepository;
import com.sentinelpay.payments.repository.WalletRepository;
import com.sentinelpay.payments.service.PaymentService;
import com.sentinelpay.payments.service.UserService;
import com.sentinelpay.payments.service.WalletService;

@SpringBootTest(properties = {
    "sentinelpay.scheduling.enabled=true",
    "sentinelpay.outbox.poll-ms=50",
    "sentinelpay.sqs.poll-ms=50"
})
@ActiveProfiles("test")
class SchedulingIntegrationTest {
    private static final String SCHEDULING_QUEUE_URL =
        AbstractIntegrationTest.createPaymentQueue("payment-events-scheduling");

    @DynamicPropertySource
    static void registerSchedulingInfrastructure(
        DynamicPropertyRegistry registry
    ) {
        AbstractIntegrationTest.registerInfrastructure(
            registry,
            () -> SCHEDULING_QUEUE_URL
        );
    }

    @Value("${sentinelpay.sqs.payment-events-url}")
    private String configuredQueueUrl;

    @Autowired
    private UserService userService;

    @Autowired
    private WalletService walletService;

    @Autowired
    private PaymentService paymentService;

    @Autowired
    private PaymentRepository paymentRepository;

    @Autowired
    private OutboxEventRepository outboxEventRepository;

    @Autowired
    private ProcessedEventRepository processedEventRepository;

    @Autowired
    private ProviderAttemptRepository providerAttemptRepository;

    @Autowired
    private PaymentReservationRepository paymentReservationRepository;

    @Autowired
    private WalletRepository walletRepository;

    @Autowired
    private UserRepository userRepository;

    @Test
    void scheduledPublisherAndConsumerProcessPaymentWithoutManualPolling()
        throws Exception {
        assertEquals(SCHEDULING_QUEUE_URL, configuredQueueUrl);

        User senderUser = userService.createUser(
            "Scheduling Sender",
            "CUSTOMER"
        );
        User receiverUser = userService.createUser(
            "Scheduling Receiver",
            "CUSTOMER"
        );
        Wallet sender = walletService.createWallet(
            senderUser.getUserId(),
            "AUD"
        );
        Wallet receiver = walletService.createWallet(
            receiverUser.getUserId(),
            "AUD"
        );
        sender.setBalance(new BigDecimal("100.00"));
        walletRepository.saveAndFlush(sender);

        Payment payment = paymentService.createPayment(
            senderUser.getUserId(),
            sender.getId(),
            receiver.getId(),
            new BigDecimal("10.00"),
            "AUD",
            "scheduled-worker-test",
            UUID.randomUUID()
        );
        OutboxEvent event = outboxEventRepository
            .findOutboxEventsByAggregateId(payment.getId())
            .getFirst();

        try {
            awaitProcessed(event.getId(), payment.getId(), Duration.ofSeconds(10));

            OutboxEvent published = outboxEventRepository
                .findById(event.getId())
                .orElseThrow();
            Payment processed = paymentRepository
                .findById(payment.getId())
                .orElseThrow();

            assertNotNull(published.getPublishedAt());
            assertEquals(PaymentStatus.PROCESSING, processed.getStatus());
        } finally {
            processedEventRepository.deleteAllById(List.of(event.getId()));
            providerAttemptRepository.deleteAllById(List.of(payment.getId()));
            paymentReservationRepository.deleteAllById(List.of(payment.getId()));
            outboxEventRepository.deleteAllById(List.of(event.getId()));
            paymentRepository.deleteAllById(List.of(payment.getId()));
            walletRepository.deleteAllById(List.of(sender.getId(), receiver.getId()));
            userRepository.deleteAllById(List.of(
                senderUser.getUserId(),
                receiverUser.getUserId()
            ));
        }
    }

    private void awaitProcessed(
        UUID eventId,
        UUID paymentId,
        Duration timeout
    ) throws InterruptedException {
        Instant deadline = Instant.now().plus(timeout);
        while (Instant.now().isBefore(deadline)) {
            boolean processed = processedEventRepository.existsById(eventId);
            PaymentStatus status = paymentRepository.findById(paymentId)
                .map(Payment::getStatus)
                .orElse(null);
            if (processed && status == PaymentStatus.PROCESSING) {
                return;
            }
            Thread.sleep(50);
        }
        fail("Scheduled workers did not process payment within " + timeout);
    }
}
