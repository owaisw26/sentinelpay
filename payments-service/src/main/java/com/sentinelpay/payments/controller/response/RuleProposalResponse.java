package com.sentinelpay.payments.controller.response;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import tools.jackson.databind.JsonNode;

public record RuleProposalResponse(
    UUID proposalId,
    String status,
    long version,
    String promptVersion,
    String schemaVersion,
    String model,
    String providerResponseId,
    String responseSha256,
    JsonNode inputSummary,
    JsonNode candidate,
    JsonNode impact,
    JsonNode usage,
    List<String> validationFailures,
    String generatedBy,
    OffsetDateTime generatedAt,
    String reviewedBy,
    OffsetDateTime reviewedAt,
    String reviewReason,
    Long approvedRulesetVersion
) {}
