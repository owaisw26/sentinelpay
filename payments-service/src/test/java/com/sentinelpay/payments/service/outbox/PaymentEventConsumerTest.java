package com.sentinelpay.payments.service.outbox;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.ChangeMessageVisibilityRequest;
import software.amazon.awssdk.services.sqs.model.DeleteMessageRequest;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.MessageSystemAttributeName;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageResponse;
import tools.jackson.databind.ObjectMapper;

class PaymentEventConsumerTest {
    @Test
    void oneFailedMessageDoesNotAbortTheRestOfTheBatch() {
        SqsClient sqs = mock(SqsClient.class);
        PaymentEventProcessor processor = mock(PaymentEventProcessor.class);
        ObjectMapper objectMapper = new ObjectMapper();
        OutboxMessage failingEvent = event();
        OutboxMessage successfulEvent = event();
        Message failingMessage = message("failed", failingEvent, objectMapper);
        Message successfulMessage = message(
            "successful",
            successfulEvent,
            objectMapper
        );

        when(sqs.receiveMessage(any(software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest.class)))
            .thenReturn(ReceiveMessageResponse.builder()
                .messages(failingMessage, successfulMessage)
                .build());
        doThrow(new IllegalStateException("forced failure"))
            .when(processor)
            .process(failingEvent);

        PaymentEventConsumer consumer = new PaymentEventConsumer(
            sqs,
            objectMapper,
            processor,
            "queue-url"
        );

        consumer.poll();

        verify(processor).process(failingEvent);
        verify(processor).process(successfulEvent);
        verify(sqs).changeMessageVisibility(
            any(ChangeMessageVisibilityRequest.class)
        );
        verify(sqs).deleteMessage(any(DeleteMessageRequest.class));
    }

    private static OutboxMessage event() {
        return new OutboxMessage(
            UUID.randomUUID(),
            UUID.randomUUID(),
            "PAYMENT_CREATED",
            UUID.randomUUID(),
            LocalDateTime.now(),
            new ObjectMapper().createObjectNode()
        );
    }

    private static Message message(
        String receiptHandle,
        OutboxMessage event,
        ObjectMapper objectMapper
    ) {
        return Message.builder()
            .messageId(UUID.randomUUID().toString())
            .receiptHandle(receiptHandle)
            .body(objectMapper.writeValueAsString(event))
            .attributes(Map.of(
                MessageSystemAttributeName.APPROXIMATE_RECEIVE_COUNT,
                "1"
            ))
            .build();
    }
}
