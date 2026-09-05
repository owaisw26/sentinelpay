package com.sentinelpay.payments.service.outbox;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import com.sentinelpay.payments.domain.OutboxEvent;

import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;
import tools.jackson.databind.ObjectMapper;

@Service
public class OutboxPublisher {
    private final OutboxClaimService outboxClaimService;
    private final SqsClient sqsClient;
    private final ObjectMapper objectMapper;
    private final String queueUrl;
    private final int batchSize;
    private final long leaseSeconds;
    private final long retryBaseMillis;
    private final long retryMaxMillis;

    public OutboxPublisher(
        OutboxClaimService outboxClaimService,
        SqsClient sqsClient,
        ObjectMapper objectMapper,
        @Value("${sentinelpay.sqs.payment-events-url}") String queueUrl,
        @Value("${sentinelpay.outbox.batch-size:50}") int batchSize,
        @Value("${sentinelpay.outbox.lease-seconds:30}") long leaseSeconds,
        @Value("${sentinelpay.outbox.retry-base-ms:1000}") long retryBaseMillis,
        @Value("${sentinelpay.outbox.retry-max-ms:300000}") long retryMaxMillis
    ) {
        this.outboxClaimService = outboxClaimService;
        this.sqsClient = sqsClient;
        this.objectMapper = objectMapper;
        this.queueUrl = queueUrl;
        this.batchSize = batchSize;
        this.leaseSeconds = leaseSeconds;
        this.retryBaseMillis = retryBaseMillis;
        this.retryMaxMillis = retryMaxMillis;
    }

    @Scheduled(fixedDelayString = "${sentinelpay.outbox.poll-ms:1000}")
    public void publishPendingEvents() {
        publishBatch();
    }

    public void publishBatch() {
        List<OutboxEvent> events = outboxClaimService.claimBatch(
            batchSize,
            leaseSeconds
        );

        RuntimeException firstFailure = null;

        for (OutboxEvent event: events) {
            try {
                OutboxMessage message = new OutboxMessage(event.getId(), event.getAggregateId(), event.getEventType(), event.getCorrelationId(), event.getCreatedAt(), event.getPayload());

                String messageBody = objectMapper.writeValueAsString(message);

                SendMessageRequest request = SendMessageRequest.builder()
                    .queueUrl(queueUrl)
                    .messageBody(messageBody)
                    .build();

                sqsClient.sendMessage(request);
                outboxClaimService.complete(event.getId(), event.getLeaseToken());
            } catch (RuntimeException failure) {
                try {
                    outboxClaimService.fail(
                        event.getId(),
                        event.getLeaseToken(),
                        retryDelayMillis(event.getAttemptCount()),
                        failure
                    );
                } catch (RuntimeException claimFailure) {
                    failure.addSuppressed(claimFailure);
                }
                if (firstFailure == null) {
                    firstFailure = failure;
                }
            }
        }

        if (firstFailure != null) {
            throw firstFailure;
        }
    }

    private long retryDelayMillis(int attemptCount) {
        int exponent = Math.min(Math.max(attemptCount - 1, 0), 20);
        long exponential;
        try {
            exponential = Math.multiplyExact(retryBaseMillis, 1L << exponent);
        } catch (ArithmeticException ignored) {
            exponential = retryMaxMillis;
        }
        long capped = Math.min(exponential, retryMaxMillis);
        long lowerBound = Math.max(1, capped / 2);
        return ThreadLocalRandom.current().nextLong(lowerBound, capped + 1);
    }
}
