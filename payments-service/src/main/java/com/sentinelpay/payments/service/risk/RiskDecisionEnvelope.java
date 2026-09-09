package com.sentinelpay.payments.service.risk;

import java.time.OffsetDateTime;
import java.util.UUID;

public record RiskDecisionEnvelope(
    UUID eventId,
    String eventType,
    int schemaVersion,
    UUID aggregateId,
    long aggregateSequence,
    OffsetDateTime occurredAt,
    UUID correlationId,
    UUID causationId,
    RiskDecisionPayload payload
) {}
