package com.sentinelpay.payments.service.outbox;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.DeleteMessageRequest;
import software.amazon.awssdk.services.sqs.model.ChangeMessageVisibilityRequest;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.MessageSystemAttributeName;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest;
import tools.jackson.databind.ObjectMapper;

@Service
public class PaymentEventConsumer {
    private static final Logger LOGGER = LoggerFactory.getLogger(
        PaymentEventConsumer.class
    );

    private final SqsClient sqsClient;
    private final ObjectMapper objectMapper;
    private final PaymentEventProcessor paymentEventProcessor;
    private final String queueUrl;
    private final int longPollSeconds;
    private final int visibilityTimeoutSeconds;
    private final int retryBaseSeconds;
    private final int retryMaxSeconds;

    @Autowired
    public PaymentEventConsumer(
        SqsClient sqsClient,
        ObjectMapper objectMapper,
        PaymentEventProcessor paymentEventProcessor,
        @Value("${sentinelpay.sqs.payment-events-url}") String queueUrl,
        @Value("${sentinelpay.sqs.long-poll-seconds:10}") int longPollSeconds,
        @Value("${sentinelpay.sqs.visibility-timeout-seconds:30}")
            int visibilityTimeoutSeconds,
        @Value("${sentinelpay.sqs.retry-base-seconds:1}") int retryBaseSeconds,
        @Value("${sentinelpay.sqs.retry-max-seconds:300}") int retryMaxSeconds
    ) {
        this.sqsClient = sqsClient;
        this.objectMapper = objectMapper;
        this.paymentEventProcessor = paymentEventProcessor;
        this.queueUrl = queueUrl;
        this.longPollSeconds = longPollSeconds;
        this.visibilityTimeoutSeconds = visibilityTimeoutSeconds;
        this.retryBaseSeconds = retryBaseSeconds;
        this.retryMaxSeconds = retryMaxSeconds;
    }

    public PaymentEventConsumer(
        SqsClient sqsClient,
        ObjectMapper objectMapper,
        PaymentEventProcessor paymentEventProcessor,
        String queueUrl
    ) {
        this(
            sqsClient,
            objectMapper,
            paymentEventProcessor,
            queueUrl,
            1,
            30,
            1,
            300
        );
    }

    @Scheduled(fixedDelayString = "${sentinelpay.sqs.poll-ms:1000}")
    public void poll() {
        ReceiveMessageRequest request = ReceiveMessageRequest.builder()
            .queueUrl(queueUrl)
            .maxNumberOfMessages(10)
            .waitTimeSeconds(longPollSeconds)
            .visibilityTimeout(visibilityTimeoutSeconds)
            .messageSystemAttributeNames(
                MessageSystemAttributeName.APPROXIMATE_RECEIVE_COUNT
            )
            .build();

        List<Message> messages =
            sqsClient.receiveMessage(request).messages();

        for (Message sqsMessage : messages) {
            processMessage(sqsMessage);
        }
    }

    private void processMessage(Message sqsMessage) {
        try {
            OutboxMessage message =
                objectMapper.readValue(
                    sqsMessage.body(),
                    OutboxMessage.class
                );

            paymentEventProcessor.process(message);

            DeleteMessageRequest deleteRequest =
                DeleteMessageRequest.builder()
                    .queueUrl(queueUrl)
                    .receiptHandle(sqsMessage.receiptHandle())
                    .build();

            sqsClient.deleteMessage(deleteRequest);

        } catch (Exception exception) {
            int receiveCount = receiveCount(sqsMessage);
            int retryDelay = retryDelaySeconds(receiveCount);
            try {
                sqsClient.changeMessageVisibility(
                    ChangeMessageVisibilityRequest.builder()
                        .queueUrl(queueUrl)
                        .receiptHandle(sqsMessage.receiptHandle())
                        .visibilityTimeout(retryDelay)
                        .build()
                );
            } catch (RuntimeException visibilityFailure) {
                LOGGER.warn(
                    "Could not apply payment event retry visibility; failureType={}",
                    visibilityFailure.getClass().getSimpleName()
                );
            }
            LOGGER.warn(
                "Payment event message failed; receiveCount={}, retryDelaySeconds={}, failureType={}",
                receiveCount,
                retryDelay,
                exception.getClass().getSimpleName()
            );
        }
    }

    private int receiveCount(Message message) {
        String value = message.attributes().get(
            MessageSystemAttributeName.APPROXIMATE_RECEIVE_COUNT
        );
        if (value == null) {
            return 1;
        }
        try {
            return Math.max(1, Integer.parseInt(value));
        } catch (NumberFormatException ignored) {
            return 1;
        }
    }

    private int retryDelaySeconds(int receiveCount) {
        int exponent = Math.min(Math.max(receiveCount - 1, 0), 20);
        long exponential = (long) Math.max(1, retryBaseSeconds) << exponent;
        int capped = (int) Math.min(
            exponential,
            Math.max(1, retryMaxSeconds)
        );
        int lowerBound = Math.max(1, capped / 2);
        return ThreadLocalRandom.current().nextInt(lowerBound, capped + 1);
    }
}
