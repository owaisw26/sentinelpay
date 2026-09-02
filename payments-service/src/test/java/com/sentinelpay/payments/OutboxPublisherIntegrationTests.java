package com.sentinelpay.payments;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.sentinelpay.payments.domain.OutboxEvent;
import com.sentinelpay.payments.domain.Payment;
import com.sentinelpay.payments.domain.PaymentStatus;
import com.sentinelpay.payments.domain.ProcessedEvent;
import com.sentinelpay.payments.domain.User;
import com.sentinelpay.payments.domain.Wallet;
import com.sentinelpay.payments.repository.OutboxEventRepository;
import com.sentinelpay.payments.repository.PaymentRepository;
import com.sentinelpay.payments.repository.ProcessedEventRepository;
import com.sentinelpay.payments.repository.UserRepository;
import com.sentinelpay.payments.repository.WalletRepository;
import com.sentinelpay.payments.service.PaymentService;
import com.sentinelpay.payments.service.UserService;
import com.sentinelpay.payments.service.WalletService;
import com.sentinelpay.payments.service.outbox.OutboxMessage;
import com.sentinelpay.payments.service.outbox.OutboxPublisher;
import com.sentinelpay.payments.service.outbox.PaymentEventConsumer;
import com.sentinelpay.payments.service.outbox.PaymentEventProcessor;

import jakarta.transaction.Transactional;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.CreateQueueRequest;
import software.amazon.awssdk.services.sqs.model.DeleteMessageRequest;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageResponse;
import software.amazon.awssdk.services.sqs.model.SqsException;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@Import(OutboxPublisherIntegrationTests.FaultInjectingSqsConfig.class)
public class OutboxPublisherIntegrationTests {
    private static final SqsClient QUEUE_ADMIN = createLocalStackClient();

    private static final String TEST_QUEUE_URL = createTestQueue();

    @DynamicPropertySource
    static void configureTestQueue(DynamicPropertyRegistry registry) {
        registry.add(
            "sentinelpay.sqs.payment-events-url",
            () -> TEST_QUEUE_URL
        );
    }

    @AfterAll
    static void closeQueueAdmin() {
        QUEUE_ADMIN.close();
    }

    @Autowired
    private OutboxEventRepository outboxEventRepository;

    @Autowired
    private UserService userService;

    @Autowired
    private WalletService walletService;

    @Autowired
    private PaymentService paymentService;

    @Autowired
    private PaymentRepository paymentRepository;

    @Autowired
    private OutboxPublisher outboxPublisher;

    @Autowired
    private PaymentEventProcessor paymentEventProcessor;

    @Autowired
    private ProcessedEventRepository processedEventRepository;

    @Autowired
    private WalletRepository walletRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PaymentEventConsumer paymentEventConsumer;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private SqsFailureControl sqsFailureControl;

    @Test
    @Transactional(Transactional.TxType.NOT_SUPPORTED)
    public void pendingOutboxEventIsPublishedToSqs() throws Exception {
        User initiatingUser = userService.createUser("Initiator", "CUSTOMER");
        User receivingUser = userService.createUser("Receiver", "CUSTOMER");

        Wallet sender =
            walletService.createWallet(initiatingUser.getUserId(), "AUD");

        Wallet receiver =
            walletService.createWallet(receivingUser.getUserId(), "AUD");

        Payment payment = paymentService.createPayment(
            initiatingUser.getUserId(),
            sender.getId(),
            receiver.getId(),
            new BigDecimal("10.00"),
            "AUD",
            "sqs-test",
            UUID.randomUUID()
        );

        OutboxEvent event = outboxEventRepository.findOutboxEventsByAggregateId(payment.getId()).get(0);
        try {
            assertNull(event.getPublishedAt());

            outboxPublisher.publishPendingEvents();

            event = outboxEventRepository
                .findOutboxEventsByAggregateId(payment.getId())
                .getFirst();

            OutboxEvent publishedEvent = outboxEventRepository
                .findById(event.getId())
                .orElseThrow();

            assertNotNull(publishedEvent.getPublishedAt());

            // Remove the message before deleting the committed test records.
            paymentEventConsumer.poll();
        } finally {
            deleteCommittedTestData(
                event.getId(),
                payment.getId(),
                sender,
                receiver,
                initiatingUser,
                receivingUser
            );
        }
    }

    @Test
    public void processWorksAppropriately() {
         User initiatingUser = userService.createUser("Initiator", "CUSTOMER");
        User receivingUser = userService.createUser("Receiver", "CUSTOMER");

        Wallet sender =
            walletService.createWallet(initiatingUser.getUserId(), "AUD");

        Wallet receiver =
            walletService.createWallet(receivingUser.getUserId(), "AUD");

        Payment payment = paymentService.createPayment(
            initiatingUser.getUserId(),
            sender.getId(),
            receiver.getId(),
            new BigDecimal("10.00"),
            "AUD",
            "sqs-test",
            UUID.randomUUID()
        );

        OutboxEvent event = outboxEventRepository.findOutboxEventsByAggregateId(payment.getId()).get(0);
        assertNull(event.getPublishedAt());

        OutboxMessage message = new OutboxMessage(event.getId(), event.getAggregateId(), event.getEventType(), event.getCorrelationId(), event.getCreatedAt(), event.getPayload());

        paymentEventProcessor.process(message);
        paymentEventProcessor.process(message);

        List<ProcessedEvent > processedEvents = processedEventRepository.findAllById(List.of(event.getId()));

        assertEquals(processedEvents.size(), 1);

        assertEquals(payment.getStatus(), PaymentStatus.SCREENING);
    }

    @Test
    @Transactional(Transactional.TxType.NOT_SUPPORTED)
    public void paymentEventFlowsThroughSqs() {
        User initiatingUser =
            userService.createUser("Initiator", "CUSTOMER");

        User receivingUser =
            userService.createUser("Receiver", "CUSTOMER");

        Wallet sender =
            walletService.createWallet(
                initiatingUser.getUserId(),
                "AUD"
            );

        Wallet receiver =
            walletService.createWallet(
                receivingUser.getUserId(),
                "AUD"
            );

        Payment payment = paymentService.createPayment(
            initiatingUser.getUserId(),
            sender.getId(),
            receiver.getId(),
            new BigDecimal("10.00"),
            "AUD",
            "sqs-e2e",
            UUID.randomUUID()
        );

        OutboxEvent event = outboxEventRepository
            .findOutboxEventsByAggregateId(payment.getId())
            .getFirst();

        try {
            assertEquals(
                PaymentStatus.CREATED,
                payment.getStatus()
            );

            // DB outbox → SQS
            outboxPublisher.publishPendingEvents();

            // SQS → processor → DB
            paymentEventConsumer.poll();

            Payment updated =
                paymentRepository.findById(payment.getId())
                    .orElseThrow();

            assertEquals(
                PaymentStatus.SCREENING,
                updated.getStatus()
            );

            OutboxEvent publishedEvent = outboxEventRepository
                .findById(event.getId())
                .orElseThrow();

            assertNotNull(publishedEvent.getPublishedAt());

            assertTrue(processedEventRepository.existsById(event.getId()));
        } finally {
            deleteCommittedTestData(
                event.getId(),
                payment.getId(),
                sender,
                receiver,
                initiatingUser,
                receivingUser
            );
        }
    }

    @Test
    @Transactional(Transactional.TxType.NOT_SUPPORTED)
    public void scheduledPollingPublishesAndProcessesPaymentEvent()
        throws Exception {
        User initiatingUser =
            userService.createUser("ScheduledInitiator", "CUSTOMER");

        User receivingUser =
            userService.createUser("ScheduledReceiver", "CUSTOMER");

        Wallet sender = walletService.createWallet(
            initiatingUser.getUserId(),
            "AUD"
        );

        Wallet receiver = walletService.createWallet(
            receivingUser.getUserId(),
            "AUD"
        );

        Payment payment = paymentService.createPayment(
            initiatingUser.getUserId(),
            sender.getId(),
            receiver.getId(),
            new BigDecimal("10.00"),
            "AUD",
            "automatic-polling-test",
            UUID.randomUUID()
        );

        OutboxEvent event = outboxEventRepository
            .findOutboxEventsByAggregateId(payment.getId())
            .getFirst();

        try {
            awaitCondition(
                () -> eventWasPublishedAndProcessed(
                    event.getId(),
                    payment.getId()
                ),
                Duration.ofSeconds(10)
            );

            OutboxEvent publishedEvent = outboxEventRepository
                .findById(event.getId())
                .orElseThrow();

            Payment processedPayment = paymentRepository
                .findById(payment.getId())
                .orElseThrow();

            assertNotNull(publishedEvent.getPublishedAt());
            assertTrue(processedEventRepository.existsById(event.getId()));
            assertEquals(PaymentStatus.SCREENING, processedPayment.getStatus());
        } finally {
            deleteCommittedTestData(
                event.getId(),
                payment.getId(),
                sender,
                receiver,
                initiatingUser,
                receivingUser
            );
        }
    }

    @Test
    @Transactional(Transactional.TxType.NOT_SUPPORTED)
    public void recoveryTest() throws Exception {
        sqsFailureControl.pauseReceiving();
        sqsFailureControl.failPublishing();

        User initiatingUser =
            userService.createUser("RecoveryInitiator", "CUSTOMER");

        User receivingUser =
            userService.createUser("RecoveryReceiver", "CUSTOMER");

        Wallet sender = walletService.createWallet(
            initiatingUser.getUserId(),
            "AUD"
        );

        Wallet receiver = walletService.createWallet(
            receivingUser.getUserId(),
            "AUD"
        );

        Payment payment = paymentService.createPayment(
            initiatingUser.getUserId(),
            sender.getId(),
            receiver.getId(),
            new BigDecimal("10.00"),
            "AUD",
            "recovery-test",
            UUID.randomUUID()
        );

        OutboxEvent event = outboxEventRepository
            .findOutboxEventsByAggregateId(payment.getId())
            .getFirst();

        int createdVersion = payment.getVersion();
        Message deliveredMessage = null;

        try {
            assertThrows(
                SqsException.class,
                outboxPublisher::publishBatch
            );

            OutboxEvent failedEvent = outboxEventRepository
                .findById(event.getId())
                .orElseThrow();

            assertNull(failedEvent.getPublishedAt());

            sqsFailureControl.restorePublishing();

            outboxPublisher.publishBatch();

            OutboxEvent publishedEvent = outboxEventRepository
                .findById(event.getId())
                .orElseThrow();

            assertNotNull(publishedEvent.getPublishedAt());

            deliveredMessage = receiveMessage(event.getId(), Duration.ofSeconds(5));

            OutboxMessage deliveredEvent = objectMapper.readValue(
                deliveredMessage.body(),
                OutboxMessage.class
            );

            assertEquals(event.getId(), deliveredEvent.eventId());

            paymentEventProcessor.process(deliveredEvent);
            paymentEventProcessor.process(deliveredEvent);

            Payment processedPayment = paymentRepository
                .findById(payment.getId())
                .orElseThrow();

            assertEquals(PaymentStatus.SCREENING, processedPayment.getStatus());
            assertEquals(createdVersion + 1, processedPayment.getVersion());
            assertEquals(
                1,
                processedEventRepository.findAllById(List.of(event.getId())).size()
            );
        } finally {
            if (deliveredMessage != null) {
                QUEUE_ADMIN.deleteMessage(
                    DeleteMessageRequest.builder()
                        .queueUrl(TEST_QUEUE_URL)
                        .receiptHandle(deliveredMessage.receiptHandle())
                        .build()
                );
            }

            deleteCommittedTestData(
                event.getId(),
                payment.getId(),
                sender,
                receiver,
                initiatingUser,
                receivingUser
            );

            sqsFailureControl.restorePublishing();
            sqsFailureControl.resumeReceiving();
        }
    }

    private boolean eventWasPublishedAndProcessed(
        UUID eventId,
        UUID paymentId
    ) {
        boolean published = outboxEventRepository.findById(eventId)
            .map(event -> event.getPublishedAt() != null)
            .orElse(false);

        boolean processed = processedEventRepository.existsById(eventId);

        boolean paymentAdvanced = paymentRepository.findById(paymentId)
            .map(payment -> payment.getStatus() == PaymentStatus.SCREENING)
            .orElse(false);

        return published && processed && paymentAdvanced;
    }

    private void awaitCondition(
        BooleanSupplier condition,
        Duration timeout
    ) throws InterruptedException {
        Instant deadline = Instant.now().plus(timeout);

        while (Instant.now().isBefore(deadline)) {
            if (condition.getAsBoolean()) {
                return;
            }

            Thread.sleep(100);
        }

        fail("Scheduled outbox polling did not complete within " + timeout);
    }

    private Message receiveMessage(
        UUID eventId,
        Duration timeout
    ) throws Exception {
        Instant deadline = Instant.now().plus(timeout);

        while (Instant.now().isBefore(deadline)) {
            List<Message> messages = QUEUE_ADMIN.receiveMessage(
                ReceiveMessageRequest.builder()
                    .queueUrl(TEST_QUEUE_URL)
                    .maxNumberOfMessages(10)
                    .waitTimeSeconds(1)
                    .build()
            ).messages();

            for (Message message : messages) {
                OutboxMessage outboxMessage = objectMapper.readValue(
                    message.body(),
                    OutboxMessage.class
                );

                if (eventId.equals(outboxMessage.eventId())) {
                    return message;
                }
            }
        }

        throw new AssertionError(
            "SQS did not receive outbox event " + eventId + " within " + timeout
        );
    }

    private void deleteCommittedTestData(
        UUID eventId,
        UUID paymentId,
        Wallet sender,
        Wallet receiver,
        User initiatingUser,
        User receivingUser
    ) {
        processedEventRepository.findById(eventId)
            .ifPresent(processedEventRepository::delete);
        outboxEventRepository.findById(eventId)
            .ifPresent(outboxEventRepository::delete);
        paymentRepository.findById(paymentId)
            .ifPresent(paymentRepository::delete);
        walletRepository.deleteAllById(
            List.of(sender.getId(), receiver.getId())
        );
        userRepository.deleteAllById(
            List.of(
                initiatingUser.getUserId(),
                receivingUser.getUserId()
            )
        );
    }

    private static String createTestQueue() {
        return QUEUE_ADMIN.createQueue(
            CreateQueueRequest.builder()
                .queueName(
                    "payment-events-integration-tests-" + UUID.randomUUID()
                )
                .build()
        ).queueUrl();
    }

    private static SqsClient createLocalStackClient() {
        return SqsClient.builder()
            .endpointOverride(URI.create("http://localhost:4566"))
            .region(Region.AP_SOUTHEAST_2)
            .credentialsProvider(
                StaticCredentialsProvider.create(
                    AwsBasicCredentials.create("test", "test")
                )
            )
            .build();
    }

    static final class SqsFailureControl {
        private final AtomicBoolean publishingFails = new AtomicBoolean();
        private final ReentrantReadWriteLock receiveLock =
            new ReentrantReadWriteLock();
        private boolean receivingPaused;

        void failPublishing() {
            publishingFails.set(true);
        }

        void restorePublishing() {
            publishingFails.set(false);
        }

        void pauseReceiving() {
            receiveLock.writeLock().lock();
            try {
                receivingPaused = true;
            } finally {
                receiveLock.writeLock().unlock();
            }
        }

        void resumeReceiving() {
            receiveLock.writeLock().lock();
            try {
                receivingPaused = false;
            } finally {
                receiveLock.writeLock().unlock();
            }
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class FaultInjectingSqsConfig {
        @Bean
        SqsFailureControl sqsFailureControl() {
            return new SqsFailureControl();
        }

        @Bean
        @Primary
        SqsClient faultInjectingSqsClient(SqsFailureControl failureControl) {
            SqsClient delegate = createLocalStackClient();

            return (SqsClient) Proxy.newProxyInstance(
                SqsClient.class.getClassLoader(),
                new Class<?>[] { SqsClient.class },
                (proxy, method, arguments) -> {
                    if (
                        method.getName().equals("sendMessage")
                            && failureControl.publishingFails.get()
                    ) {
                        throw SqsException.builder()
                            .message("Forced SQS publishing failure")
                            .build();
                    }

                    if (method.getName().equals("receiveMessage")) {
                        failureControl.receiveLock.readLock().lock();
                        try {
                            if (failureControl.receivingPaused) {
                                return ReceiveMessageResponse.builder()
                                    .messages(List.of())
                                    .build();
                            }

                            return invokeDelegate(
                                delegate,
                                method,
                                arguments
                            );
                        } finally {
                            failureControl.receiveLock.readLock().unlock();
                        }
                    }

                    return invokeDelegate(delegate, method, arguments);
                }
            );
        }

        private static Object invokeDelegate(
            SqsClient delegate,
            java.lang.reflect.Method method,
            Object[] arguments
        ) throws Throwable {
            try {
                return method.invoke(delegate, arguments);
            } catch (InvocationTargetException exception) {
                throw exception.getCause();
            }
        }
    }
}
