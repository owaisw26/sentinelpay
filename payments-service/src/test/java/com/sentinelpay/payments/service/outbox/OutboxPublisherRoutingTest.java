package com.sentinelpay.payments.service.outbox;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.sentinelpay.payments.domain.OutboxEvent;

import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;
import software.amazon.awssdk.services.sqs.model.SendMessageResponse;
import tools.jackson.databind.ObjectMapper;

class OutboxPublisherRoutingTest {
    @Test
    void screeningEnvelopeIsRoutedToFraudQueue() {
        OutboxClaimService claims = mock(OutboxClaimService.class);
        SqsClient sqs = mock(SqsClient.class);
        ObjectMapper objectMapper = new ObjectMapper();
        UUID paymentId = UUID.randomUUID();
        OutboxEvent event = new OutboxEvent(
            paymentId,
            "PAYMENT_SCREENING_REQUESTED",
            1,
            objectMapper.valueToTree(java.util.Map.of("paymentId", paymentId)),
            UUID.randomUUID(),
            null
        );
        UUID leaseToken = UUID.randomUUID();
        event.claim(
            leaseToken,
            LocalDateTime.now().plusSeconds(30),
            LocalDateTime.now()
        );
        when(claims.claimBatch(50, 30)).thenReturn(List.of(event));
        when(sqs.sendMessage(any(SendMessageRequest.class)))
            .thenReturn(SendMessageResponse.builder().build());
        OutboxPublisher publisher = new OutboxPublisher(
            claims, sqs, objectMapper, "payment-queue", "fraud-queue",
            50, 30, 1000, 300000
        );

        publisher.publishBatch();

        ArgumentCaptor<SendMessageRequest> request =
            ArgumentCaptor.forClass(SendMessageRequest.class);
        verify(sqs).sendMessage(request.capture());
        assertEquals("fraud-queue", request.getValue().queueUrl());
        OutboxMessage envelope = objectMapper.readValue(
            request.getValue().messageBody(), OutboxMessage.class
        );
        assertEquals("PAYMENT_SCREENING_REQUESTED", envelope.eventType());
        assertEquals(1, envelope.schemaVersion());
        assertEquals(1, envelope.aggregateSequence());
        assertNotNull(envelope.occurredAt().getOffset());
        verify(claims).complete(event.getId(), leaseToken);
    }
}
