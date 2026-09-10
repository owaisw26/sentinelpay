package com.sentinelpay.payments.controller.response;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import com.sentinelpay.payments.repository.RiskDecisionSummary;

public record RiskExplanationResponse(
    UUID decisionId,
    int score,
    List<String> reasonCodes,
    String featureVersion,
    String rulesetVersion,
    String modelVersion,
    List<UUID> matchedRuleIds,
    OffsetDateTime decidedAt
) {
    public static RiskExplanationResponse from(RiskDecisionSummary summary) {
        return new RiskExplanationResponse(
            summary.decisionId(), summary.score(), summary.reasonCodes(),
            summary.featureVersion(), summary.rulesetVersion(),
            summary.modelVersion(), summary.matchedRuleIds(),
            summary.receivedAt()
        );
    }
}
