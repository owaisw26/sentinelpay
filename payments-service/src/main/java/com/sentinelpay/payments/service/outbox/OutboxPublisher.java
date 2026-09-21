package com.sentinelpay.payments.service.outbox;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import com.sentinelpay.payments.domain.OutboxEvent;

import software.amazon.awssdk.services.sns.SnsClient;
import software.amazon.awssdk.services.sns.model.MessageAttributeValue;
import software.amazon.awssdk.services.sns.model.PublishRequest;
import tools.jackson.databind.ObjectMapper;

@Service
public class OutboxPublisher {
    private final OutboxClaimService outboxClaimService;
    private final SnsClient snsClient;
    private final ObjectMapper objectMapper;
    private final String topicArn;
    private final int batchSize;
    private final long leaseSeconds;
    private final long retryBaseMillis;
    private final long retryMaxMillis;

    public OutboxPublisher(
        OutboxClaimService outboxClaimService,
        SnsClient snsClient,
        ObjectMapper objectMapper,
        @Value("${sentinelpay.sns.payment-events-topic-arn}") String topicArn,
        @Value("${sentinelpay.outbox.batch-size:50}") int batchSize,
        @Value("${sentinelpay.outbox.lease-seconds:30}") long leaseSeconds,
        @Value("${sentinelpay.outbox.retry-base-ms:1000}") long retryBaseMillis,
        @Value("${sentinelpay.outbox.retry-max-ms:300000}") long retryMaxMillis
    ) {
        this.outboxClaimService = outboxClaimService;
        this.snsClient = snsClient;
        this.objectMapper = objectMapper;
        this.topicArn = topicArn;
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
                OutboxMessage message = new OutboxMessage(
                    event.getId(),
                    event.getEventType(),
                    event.getSchemaVersion(),
                    event.getAggregateId(),
                    event.getAggregateSequence(),
                    event.getOccurredAt(),
                    event.getCorrelationId(),
                    event.getCausationId(),
                    event.getPayload()
                );

                String messageBody = objectMapper.writeValueAsString(message);

                PublishRequest request = PublishRequest.builder()
                    .topicArn(topicArn)
                    .message(messageBody)
                    .messageAttributes(Map.of(
                        "eventType", stringAttribute(event.getEventType()),
                        "schemaVersion", numberAttribute(event.getSchemaVersion()),
                        "traceparent", stringAttribute(traceparent(event))
                    ))
                    .build();

                snsClient.publish(request);
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

    private MessageAttributeValue stringAttribute(String value) {
        return MessageAttributeValue.builder()
            .dataType("String")
            .stringValue(value)
            .build();
    }

    private MessageAttributeValue numberAttribute(int value) {
        return MessageAttributeValue.builder()
            .dataType("Number")
            .stringValue(Integer.toString(value))
            .build();
    }

    private String traceparent(OutboxEvent event) {
        String traceId = event.getCorrelationId().toString().replace("-", "");
        String spanId = event.getId().toString().replace("-", "")
            .substring(0, 16);
        return "00-" + traceId + "-" + spanId + "-01";
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
