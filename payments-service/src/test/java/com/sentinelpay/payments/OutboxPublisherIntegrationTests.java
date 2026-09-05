package com.sentinelpay.payments;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;

import com.sentinelpay.payments.domain.OutboxEvent;
import com.sentinelpay.payments.domain.Payment;
import com.sentinelpay.payments.domain.PaymentStatus;
import com.sentinelpay.payments.domain.ProcessedEvent;
import com.sentinelpay.payments.domain.User;
import com.sentinelpay.payments.domain.Wallet;
import com.sentinelpay.payments.provider.FakePaymentProvider;
import com.sentinelpay.payments.provider.PaymentProviderResponse;
import com.sentinelpay.payments.provider.PaymentProviderTimeoutException;
import com.sentinelpay.payments.exception.ProviderAttemptInProgressException;
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
import com.sentinelpay.payments.service.outbox.OutboxMessage;
import com.sentinelpay.payments.service.outbox.OutboxPublisher;
import com.sentinelpay.payments.service.outbox.PaymentEventConsumer;
import com.sentinelpay.payments.service.outbox.PaymentEventProcessor;
import com.sentinelpay.payments.service.outbox.PaymentProcessingService;

import jakarta.transaction.Transactional;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.CreateQueueRequest;
import software.amazon.awssdk.services.sqs.model.DeleteMessageRequest;
import software.amazon.awssdk.services.sqs.model.DeleteQueueRequest;
import software.amazon.awssdk.services.sqs.model.GetQueueAttributesRequest;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.QueueAttributeName;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageResponse;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;
import software.amazon.awssdk.services.sqs.model.SqsException;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@Import(OutboxPublisherIntegrationTests.FaultInjectingSqsConfig.class)
public class OutboxPublisherIntegrationTests extends AbstractIntegrationTest {
    private static final SqsClient QUEUE_ADMIN = newSqsClient();

    private static final String TEST_QUEUE_URL = paymentQueueUrl();

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
    private PaymentProcessingService paymentProcessingService;

    @Autowired
    private ProcessedEventRepository processedEventRepository;

    @Autowired
    private PaymentReservationRepository paymentReservationRepository;

    @Autowired
    private ProviderAttemptRepository providerAttemptRepository;

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

        fund(sender);

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

        fund(sender);

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

        assertEquals(payment.getStatus(), PaymentStatus.PROCESSING);
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

        fund(sender);

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
                PaymentStatus.PROCESSING,
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
    public void timeoutLeavesMessageForRetryWithSamePaymentIdentity()
        throws Exception {
        String timeoutDlqName = "payment-timeout-dlq-" + UUID.randomUUID();
        String timeoutQueueName = "payment-timeout-test-" + UUID.randomUUID();
        String timeoutDlqUrl = QUEUE_ADMIN.createQueue(
            CreateQueueRequest.builder().queueName(timeoutDlqName).build()
        ).queueUrl();
        String timeoutDlqArn = QUEUE_ADMIN.getQueueAttributes(
            GetQueueAttributesRequest.builder()
                .queueUrl(timeoutDlqUrl)
                .attributeNames(QueueAttributeName.QUEUE_ARN)
                .build()
        ).attributes().get(QueueAttributeName.QUEUE_ARN);
        String timeoutQueueUrl = QUEUE_ADMIN.createQueue(
            CreateQueueRequest.builder()
                .queueName(timeoutQueueName)
                .attributes(Map.of(
                    QueueAttributeName.REDRIVE_POLICY,
                    "{\"deadLetterTargetArn\":\"%s\",\"maxReceiveCount\":\"3\"}"
                        .formatted(timeoutDlqArn),
                    QueueAttributeName.VISIBILITY_TIMEOUT,
                    "1"
                ))
                .build()
        ).queueUrl();
        Map<QueueAttributeName, String> queueAttributes =
            QUEUE_ADMIN.getQueueAttributes(
                GetQueueAttributesRequest.builder()
                    .queueUrl(timeoutQueueUrl)
                    .attributeNames(
                        QueueAttributeName.REDRIVE_POLICY,
                        QueueAttributeName.VISIBILITY_TIMEOUT
                    )
                    .build()
            ).attributes();
        assertEquals(
            "1",
            queueAttributes.get(QueueAttributeName.VISIBILITY_TIMEOUT)
        );
        assertTrue(
            queueAttributes.get(QueueAttributeName.REDRIVE_POLICY)
                .contains("\"maxReceiveCount\":\"3\"")
        );
        assertTrue(
            queueAttributes.get(QueueAttributeName.REDRIVE_POLICY)
                .contains(timeoutDlqName)
        );

        User initiatingUser = userService.createUser(
            "Timeout Initiator",
            "CUSTOMER"
        );
        User receivingUser = userService.createUser(
            "Timeout Receiver",
            "CUSTOMER"
        );
        Wallet sender = walletService.createWallet(
            initiatingUser.getUserId(),
            "AUD"
        );
        Wallet receiver = walletService.createWallet(
            receivingUser.getUserId(),
            "AUD"
        );
        fund(sender);
        UUID idempotencyKey = UUID.randomUUID();
        Payment payment = paymentService.createPayment(
            initiatingUser.getUserId(),
            sender.getId(),
            receiver.getId(),
            new BigDecimal("10.00"),
            "AUD",
            "psp-timeout",
            idempotencyKey
        );
        OutboxEvent event = outboxEventRepository
            .findOutboxEventsByAggregateId(payment.getId())
            .getFirst();

        try {
            OutboxMessage originalMessage = new OutboxMessage(
                event.getId(),
                event.getAggregateId(),
                event.getEventType(),
                event.getCorrelationId(),
                event.getCreatedAt(),
                event.getPayload()
            );
            QUEUE_ADMIN.sendMessage(
                SendMessageRequest.builder()
                    .queueUrl(timeoutQueueUrl)
                    .messageBody(objectMapper.writeValueAsString(originalMessage))
                    .build()
            );

            RecordingTimeoutPaymentProvider timeoutProvider =
                new RecordingTimeoutPaymentProvider();
            PaymentEventProcessor timeoutProcessor = new PaymentEventProcessor(
                paymentProcessingService,
                timeoutProvider
            );
            PaymentEventConsumer timeoutConsumer = new PaymentEventConsumer(
                QUEUE_ADMIN,
                objectMapper,
                timeoutProcessor,
                timeoutQueueUrl
            );

            timeoutConsumer.poll();

            Payment afterFirstAttempt = paymentRepository
                .findById(payment.getId())
                .orElseThrow();
            assertEquals(PaymentStatus.PROCESSING, afterFirstAttempt.getStatus());
            assertEquals(idempotencyKey, afterFirstAttempt.getIdempotencyKey());
            assertFalse(processedEventRepository.existsById(event.getId()));

            awaitConsumerAttempts(
                timeoutConsumer,
                timeoutProvider,
                2,
                Duration.ofSeconds(5)
            );

            assertTrue(timeoutProvider.paymentIds.size() >= 2);
            assertTrue(
                timeoutProvider.paymentIds.stream()
                    .allMatch(payment.getId()::equals)
            );
            assertTrue(
                timeoutProvider.idempotencyKeys.stream()
                    .allMatch(payment.getId()::equals)
            );

            Payment afterRetry = paymentRepository
                .findById(payment.getId())
                .orElseThrow();
            assertEquals(payment.getId(), afterRetry.getId());
            assertEquals(idempotencyKey, afterRetry.getIdempotencyKey());
            assertFalse(processedEventRepository.existsById(event.getId()));
        } finally {
            QUEUE_ADMIN.deleteQueue(
                DeleteQueueRequest.builder()
                    .queueUrl(timeoutQueueUrl)
                    .build()
            );
            QUEUE_ADMIN.deleteQueue(
                DeleteQueueRequest.builder()
                    .queueUrl(timeoutDlqUrl)
                    .build()
            );
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
    void concurrentDeliveriesCreateOneProviderOperation() throws Exception {
        User initiatingUser = userService.createUser(
            "Concurrent Initiator",
            "CUSTOMER"
        );
        User receivingUser = userService.createUser(
            "Concurrent Receiver",
            "CUSTOMER"
        );
        Wallet sender = walletService.createWallet(
            initiatingUser.getUserId(),
            "AUD"
        );
        Wallet receiver = walletService.createWallet(
            receivingUser.getUserId(),
            "AUD"
        );
        fund(sender);
        Payment payment = paymentService.createPayment(
            initiatingUser.getUserId(),
            sender.getId(),
            receiver.getId(),
            new BigDecimal("10.00"),
            "AUD",
            "concurrent-delivery",
            UUID.randomUUID()
        );
        OutboxEvent event = outboxEventRepository
            .findOutboxEventsByAggregateId(payment.getId())
            .getFirst();
        OutboxMessage message = new OutboxMessage(
            event.getId(),
            event.getAggregateId(),
            event.getEventType(),
            event.getCorrelationId(),
            event.getCreatedAt(),
            event.getPayload()
        );
        BlockingPaymentProvider provider = new BlockingPaymentProvider();
        PaymentEventProcessor processor = new PaymentEventProcessor(
            paymentProcessingService,
            provider
        );

        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<?> first = executor.submit(() -> processor.process(message));
            assertTrue(provider.awaitFirstCall(Duration.ofSeconds(5)));

            Future<?> duplicate = executor.submit(() -> processor.process(message));
            ExecutionException duplicateFailure = assertThrows(
                ExecutionException.class,
                () -> duplicate.get(5, TimeUnit.SECONDS)
            );
            assertTrue(
                duplicateFailure.getCause()
                    instanceof ProviderAttemptInProgressException
            );

            provider.releaseFirstCall();
            first.get(5, TimeUnit.SECONDS);

            assertEquals(1, provider.callCount());
            assertTrue(processedEventRepository.existsById(event.getId()));
        } finally {
            provider.releaseFirstCall();
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
    public void publisherAndConsumerProcessPaymentEvent()
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

        fund(sender);

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
            outboxPublisher.publishPendingEvents();
            paymentEventConsumer.poll();

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
            assertEquals(PaymentStatus.PROCESSING, processedPayment.getStatus());
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

        fund(sender);

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

            awaitCondition(
                () -> {
                    outboxPublisher.publishBatch();
                    return outboxEventRepository.findById(event.getId())
                        .map(candidate -> candidate.getPublishedAt() != null)
                        .orElse(false);
                },
                Duration.ofSeconds(2)
            );

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

            assertEquals(PaymentStatus.PROCESSING, processedPayment.getStatus());
            assertEquals(createdVersion + 2, processedPayment.getVersion());
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
            .map(payment -> payment.getStatus() == PaymentStatus.PROCESSING)
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
        return receiveMessage(TEST_QUEUE_URL, eventId, timeout);
    }

    private Message receiveMessage(
        String queueUrl,
        UUID eventId,
        Duration timeout
    ) throws Exception {
        Instant deadline = Instant.now().plus(timeout);

        while (Instant.now().isBefore(deadline)) {
            List<Message> messages = QUEUE_ADMIN.receiveMessage(
                ReceiveMessageRequest.builder()
                    .queueUrl(queueUrl)
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
        providerAttemptRepository.findById(paymentId)
            .ifPresent(providerAttemptRepository::delete);
        paymentReservationRepository.findById(paymentId)
            .ifPresent(paymentReservationRepository::delete);
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

    private static SqsClient createLocalStackClient() {
        return newSqsClient();
    }

    private void awaitConsumerAttempts(
        PaymentEventConsumer consumer,
        RecordingTimeoutPaymentProvider provider,
        int expectedAttempts,
        Duration timeout
    ) throws InterruptedException {
        Instant deadline = Instant.now().plus(timeout);

        while (Instant.now().isBefore(deadline)) {
            consumer.poll();
            if (provider.paymentIds.size() >= expectedAttempts) {
                return;
            }
        }

        throw new AssertionError(
            "SQS message did not reach " + expectedAttempts
                + " attempts within " + timeout
        );
    }

    private void fund(Wallet wallet) {
        wallet.setBalance(new BigDecimal("100.00"));
        walletRepository.saveAndFlush(wallet);
    }

    private static final class RecordingTimeoutPaymentProvider
        extends FakePaymentProvider {
        private final List<UUID> paymentIds = new ArrayList<>();
        private final List<UUID> idempotencyKeys = new ArrayList<>();

        private RecordingTimeoutPaymentProvider() {
            super("TIMEOUT");
        }

        @Override
        public PaymentProviderResponse processPayment(
            Payment payment,
            UUID providerIdempotencyKey
        ) {
            paymentIds.add(payment.getId());
            idempotencyKeys.add(providerIdempotencyKey);
            return super.processPayment(payment, providerIdempotencyKey);
        }
    }

    private static final class BlockingPaymentProvider
        extends FakePaymentProvider {
        private final java.util.concurrent.atomic.AtomicInteger calls =
            new java.util.concurrent.atomic.AtomicInteger();
        private final CountDownLatch firstCallEntered = new CountDownLatch(1);
        private final CountDownLatch releaseFirstCall = new CountDownLatch(1);

        private BlockingPaymentProvider() {
            super("SUCCESS");
        }

        @Override
        public PaymentProviderResponse processPayment(
            Payment payment,
            UUID providerIdempotencyKey
        ) {
            calls.incrementAndGet();
            firstCallEntered.countDown();
            try {
                if (!releaseFirstCall.await(5, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("Provider test call timed out");
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(exception);
            }
            return super.processPayment(payment, providerIdempotencyKey);
        }

        private boolean awaitFirstCall(Duration timeout)
            throws InterruptedException {
            return firstCallEntered.await(timeout.toMillis(), TimeUnit.MILLISECONDS);
        }

        private void releaseFirstCall() {
            releaseFirstCall.countDown();
        }

        private int callCount() {
            return calls.get();
        }
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
