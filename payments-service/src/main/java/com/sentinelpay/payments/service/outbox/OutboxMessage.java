package com.sentinelpay.payments.service.outbox;
import java.util.UUID;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import tools.jackson.databind.JsonNode;
public record OutboxMessage(
    UUID eventId,
    String eventType,
    int schemaVersion,
    UUID aggregateId,
    long aggregateSequence,
    OffsetDateTime occurredAt,
    UUID correlationId,
    UUID causationId,
    JsonNode payload
) {
    public OutboxMessage(
        UUID eventId,
        UUID aggregateId,
        String eventType,
        UUID correlationId,
        LocalDateTime createdAt,
        JsonNode payload
    ) {
        this(
            eventId, eventType, 1, aggregateId, 1,
            createdAt == null
                ? OffsetDateTime.now(ZoneOffset.UTC)
                : createdAt.atOffset(ZoneOffset.UTC),
            correlationId, null, payload
        );
    }
}
