package com.sentinelpay.payments.controller;

import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.sentinelpay.payments.controller.request.RuleDeactivationRequest;
import com.sentinelpay.payments.controller.request.RuleProposalReviewRequest;
import com.sentinelpay.payments.controller.request.RulesetRollbackRequest;
import com.sentinelpay.payments.controller.response.RuleProposalResponse;
import com.sentinelpay.payments.controller.response.RuleSetResponse;
import com.sentinelpay.payments.service.risk.FraudRuleProposalGateway;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;

@RestController
@RequestMapping("/analyst/risk")
@PreAuthorize("hasAuthority('ANALYST')")
@Validated
public class AnalystRiskRuleController {
    private final FraudRuleProposalGateway ruleProposals;

    public AnalystRiskRuleController(FraudRuleProposalGateway ruleProposals) {
        this.ruleProposals = ruleProposals;
    }

    @PostMapping("/rule-proposals")
    @ResponseStatus(HttpStatus.CREATED)
    public RuleProposalResponse generate(Authentication authentication) {
        return ruleProposals.generate(authentication.getName());
    }

    @GetMapping("/rule-proposals/{proposalId}")
    public RuleProposalResponse get(
        @PathVariable UUID proposalId,
        Authentication authentication
    ) {
        return ruleProposals.get(proposalId, authentication.getName());
    }

    @PostMapping("/rule-proposals/{proposalId}/approve")
    public RuleSetResponse approve(
        @PathVariable UUID proposalId,
        @Valid @RequestBody RuleProposalReviewRequest request,
        Authentication authentication
    ) {
        return ruleProposals.approve(
            proposalId,
            request.expectedVersion(),
            request.reason(),
            authentication.getName()
        );
    }

    @PostMapping("/rule-proposals/{proposalId}/reject")
    public RuleProposalResponse reject(
        @PathVariable UUID proposalId,
        @Valid @RequestBody RuleProposalReviewRequest request,
        Authentication authentication
    ) {
        return ruleProposals.reject(
            proposalId,
            request.expectedVersion(),
            request.reason(),
            authentication.getName()
        );
    }

    @PostMapping("/rules/{ruleId}/deactivate")
    public RuleSetResponse deactivate(
        @PathVariable UUID ruleId,
        @Valid @RequestBody RuleDeactivationRequest request,
        Authentication authentication
    ) {
        return ruleProposals.deactivate(
            ruleId,
            request.expectedRulesetVersion(),
            request.reason(),
            authentication.getName()
        );
    }

    @PostMapping("/rulesets/{targetVersion}/rollback")
    public RuleSetResponse rollback(
        @PathVariable @Min(0) long targetVersion,
        @Valid @RequestBody RulesetRollbackRequest request,
        Authentication authentication
    ) {
        return ruleProposals.rollback(
            targetVersion,
            request.expectedRulesetVersion(),
            request.reason(),
            authentication.getName()
        );
    }

    @GetMapping("/rulesets/active")
    public RuleSetResponse active(Authentication authentication) {
        return ruleProposals.active(authentication.getName());
    }
}
