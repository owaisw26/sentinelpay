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

import software.amazon.awssdk.services.sns.SnsClient;
import software.amazon.awssdk.services.sns.model.PublishRequest;
import software.amazon.awssdk.services.sns.model.PublishResponse;
import tools.jackson.databind.ObjectMapper;

class OutboxPublisherRoutingTest {
    @Test
    void envelopeIsPublishedWithRoutingAndTraceAttributes() {
        OutboxClaimService claims = mock(OutboxClaimService.class);
        SnsClient sns = mock(SnsClient.class);
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
        when(sns.publish(any(PublishRequest.class)))
            .thenReturn(PublishResponse.builder().build());
        OutboxPublisher publisher = new OutboxPublisher(
            claims, sns, objectMapper, "payment-topic",
            50, 30, 1000, 300000
        );

        publisher.publishBatch();

        ArgumentCaptor<PublishRequest> request =
            ArgumentCaptor.forClass(PublishRequest.class);
        verify(sns).publish(request.capture());
        assertEquals("payment-topic", request.getValue().topicArn());
        OutboxMessage envelope = objectMapper.readValue(
            request.getValue().message(), OutboxMessage.class
        );
        assertEquals("PAYMENT_SCREENING_REQUESTED", envelope.eventType());
        assertEquals(1, envelope.schemaVersion());
        assertEquals(1, envelope.aggregateSequence());
        assertNotNull(envelope.occurredAt().getOffset());
        assertEquals(
            "PAYMENT_SCREENING_REQUESTED",
            request.getValue().messageAttributes().get("eventType")
                .stringValue()
        );
        assertEquals(
            "1",
            request.getValue().messageAttributes().get("schemaVersion")
                .stringValue()
        );
        assertNotNull(
            request.getValue().messageAttributes().get("traceparent")
        );
        verify(claims).complete(event.getId(), leaseToken);
    }
}
