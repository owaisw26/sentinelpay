package com.sentinelpay.payments.controller.response;

import java.time.OffsetDateTime;
import java.util.UUID;

import tools.jackson.databind.JsonNode;

public record RuleSetResponse(
    long version,
    Long parentVersion,
    String operation,
    JsonNode rules,
    UUID sourceProposalId,
    String createdBy,
    OffsetDateTime createdAt,
    String reason
) {}
