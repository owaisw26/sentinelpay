package com.sentinelpay.payments.service.outbox;
import java.util.UUID;
import java.time.LocalDateTime;
import tools.jackson.databind.JsonNode;
public record OutboxMessage(
    UUID eventId,
    UUID aggregateId,
    String eventType,
    UUID correlationId,
    LocalDateTime createdAt,
    JsonNode payload
) {}
