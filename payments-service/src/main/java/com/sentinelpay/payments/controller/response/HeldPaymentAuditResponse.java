package com.sentinelpay.payments.controller.response;

import java.time.OffsetDateTime;
import java.util.UUID;

import tools.jackson.databind.JsonNode;

public record HeldPaymentAuditResponse(
    UUID auditId,
    UUID paymentId,
    String action,
    String actorId,
    String reason,
    JsonNode details,
    OffsetDateTime occurredAt
) {}
