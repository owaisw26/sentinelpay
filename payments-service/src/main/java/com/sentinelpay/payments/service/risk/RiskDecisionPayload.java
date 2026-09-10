package com.sentinelpay.payments.service.risk;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public record RiskDecisionPayload(
    UUID decisionId,
    UUID paymentId,
    UUID sourceEventId,
    long sourceAggregateSequence,
    String featureVersion,
    String rulesetVersion,
    String modelVersion,
    int score,
    RiskAction action,
    List<String> reasonCodes,
    List<UUID> matchedRuleIds,
    OffsetDateTime evaluatedAt
) {}
