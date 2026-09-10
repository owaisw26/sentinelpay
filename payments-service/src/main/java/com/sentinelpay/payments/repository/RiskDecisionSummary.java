package com.sentinelpay.payments.repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public record RiskDecisionSummary(
    UUID decisionId,
    int score,
    List<String> reasonCodes,
    String featureVersion,
    String rulesetVersion,
    String modelVersion,
    List<UUID> matchedRuleIds,
    OffsetDateTime receivedAt
) {}
