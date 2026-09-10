package com.sentinelpay.payments.service.risk;

import java.util.UUID;

import com.sentinelpay.payments.controller.response.RuleProposalResponse;
import com.sentinelpay.payments.controller.response.RuleSetResponse;

public interface FraudRuleProposalGateway {
    RuleProposalResponse generate(String analystSubject);

    RuleProposalResponse get(UUID proposalId, String analystSubject);

    RuleSetResponse approve(
        UUID proposalId,
        long expectedVersion,
        String reason,
        String analystSubject
    );

    RuleProposalResponse reject(
        UUID proposalId,
        long expectedVersion,
        String reason,
        String analystSubject
    );

    RuleSetResponse deactivate(
        UUID ruleId,
        long expectedRulesetVersion,
        String reason,
        String analystSubject
    );

    RuleSetResponse rollback(
        long targetVersion,
        long expectedRulesetVersion,
        String reason,
        String analystSubject
    );

    RuleSetResponse active(String analystSubject);
}
