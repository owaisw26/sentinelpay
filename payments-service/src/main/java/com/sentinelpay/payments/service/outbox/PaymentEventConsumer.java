package com.sentinelpay.payments.service.outbox;

import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.DeleteMessageRequest;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest;
import tools.jackson.databind.ObjectMapper;

@Service
public class PaymentEventConsumer {

    private final SqsClient sqsClient;
    private final ObjectMapper objectMapper;
    private final PaymentEventProcessor paymentEventProcessor;
    private final String queueUrl;

    public PaymentEventConsumer(
        SqsClient sqsClient,
        ObjectMapper objectMapper,
        PaymentEventProcessor paymentEventProcessor,
        @Value("${sentinelpay.sqs.payment-events-url}") String queueUrl
    ) {
        this.sqsClient = sqsClient;
        this.objectMapper = objectMapper;
        this.paymentEventProcessor = paymentEventProcessor;
        this.queueUrl = queueUrl;
    }

    @Scheduled(fixedDelayString = "${sentinelpay.sqs.poll-ms:1000}")
    public void poll() {
        ReceiveMessageRequest request = ReceiveMessageRequest.builder()
            .queueUrl(queueUrl)
            .maxNumberOfMessages(10)
            .waitTimeSeconds(1)
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
            // Don't delete it.
            // SQS can deliver it again later.
            throw new RuntimeException(
                "Failed to process SQS payment event",
                exception
            );
        }
    }
}