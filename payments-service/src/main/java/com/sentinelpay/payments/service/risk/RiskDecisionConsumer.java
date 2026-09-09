package com.sentinelpay.payments.service.risk;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.ChangeMessageVisibilityRequest;
import software.amazon.awssdk.services.sqs.model.DeleteMessageRequest;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.MessageSystemAttributeName;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest;

@Service
@ConditionalOnProperty(
    name = "sentinelpay.risk.enabled",
    havingValue = "true"
)
public class RiskDecisionConsumer {
    private static final Logger LOGGER = LoggerFactory.getLogger(
        RiskDecisionConsumer.class
    );

    private final SqsClient sqsClient;
    private final RiskDecisionMessageParser parser;
    private final RiskDecisionService service;
    private final String queueUrl;
    private final int longPollSeconds;
    private final int visibilityTimeoutSeconds;
    private final int retryBaseSeconds;
    private final int retryMaxSeconds;

    public RiskDecisionConsumer(
        SqsClient sqsClient,
        RiskDecisionMessageParser parser,
        RiskDecisionService service,
        @Value("${sentinelpay.sqs.risk-decisions-url}") String queueUrl,
        @Value("${sentinelpay.sqs.long-poll-seconds:10}") int longPollSeconds,
        @Value("${sentinelpay.sqs.visibility-timeout-seconds:30}")
            int visibilityTimeoutSeconds,
        @Value("${sentinelpay.sqs.retry-base-seconds:1}") int retryBaseSeconds,
        @Value("${sentinelpay.sqs.retry-max-seconds:300}") int retryMaxSeconds
    ) {
        this.sqsClient = sqsClient;
        this.parser = parser;
        this.service = service;
        this.queueUrl = queueUrl;
        this.longPollSeconds = longPollSeconds;
        this.visibilityTimeoutSeconds = visibilityTimeoutSeconds;
        this.retryBaseSeconds = retryBaseSeconds;
        this.retryMaxSeconds = retryMaxSeconds;
    }

    @Scheduled(fixedDelayString = "${sentinelpay.risk.poll-ms:1000}")
    public void poll() {
        List<Message> messages = sqsClient.receiveMessage(
            ReceiveMessageRequest.builder()
                .queueUrl(queueUrl)
                .maxNumberOfMessages(10)
                .waitTimeSeconds(longPollSeconds)
                .visibilityTimeout(visibilityTimeoutSeconds)
                .messageSystemAttributeNames(
                    MessageSystemAttributeName.APPROXIMATE_RECEIVE_COUNT
                )
                .build()
        ).messages();
        for (Message message : messages) {
            process(message);
        }
    }

    private void process(Message message) {
        try {
            service.apply(parser.parse(message.body()));
            sqsClient.deleteMessage(DeleteMessageRequest.builder()
                .queueUrl(queueUrl)
                .receiptHandle(message.receiptHandle())
                .build());
        } catch (Exception failure) {
            int receiveCount = receiveCount(message);
            int retryDelay = retryDelaySeconds(receiveCount);
            try {
                sqsClient.changeMessageVisibility(
                    ChangeMessageVisibilityRequest.builder()
                        .queueUrl(queueUrl)
                        .receiptHandle(message.receiptHandle())
                        .visibilityTimeout(retryDelay)
                        .build()
                );
            } catch (RuntimeException visibilityFailure) {
                LOGGER.warn(
                    "Could not apply risk decision retry visibility; failureType={}",
                    visibilityFailure.getClass().getSimpleName()
                );
            }
            LOGGER.warn(
                "Risk decision message failed; receiveCount={}, " +
                    "retryDelaySeconds={}, failureType={}",
                receiveCount, retryDelay, failure.getClass().getSimpleName()
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
            exponential, Math.max(1, retryMaxSeconds)
        );
        int lowerBound = Math.max(1, capped / 2);
        return ThreadLocalRandom.current().nextInt(lowerBound, capped + 1);
    }
}
