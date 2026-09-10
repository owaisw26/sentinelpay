package com.sentinelpay.payments.controller;

import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.sentinelpay.payments.controller.request.ReconciliationResolutionRequest;
import com.sentinelpay.payments.controller.request.HeldPaymentDecisionRequest;
import com.sentinelpay.payments.controller.response.HeldPaymentAuditResponse;
import com.sentinelpay.payments.controller.response.HeldPaymentPageResponse;
import com.sentinelpay.payments.controller.response.HeldPaymentResponse;
import com.sentinelpay.payments.controller.response.ReconciliationAuditResponse;
import com.sentinelpay.payments.controller.response.ReconciliationDiscrepancyPageResponse;
import com.sentinelpay.payments.controller.response.ReconciliationDiscrepancyResponse;
import com.sentinelpay.payments.controller.response.ReconciliationRunResponse;
import com.sentinelpay.payments.domain.ReconciliationDiscrepancyStatus;
import com.sentinelpay.payments.service.reconciliation.ReconciliationDetector;
import com.sentinelpay.payments.service.reconciliation.ReconciliationQueryService;
import com.sentinelpay.payments.service.reconciliation.ReconciliationResolutionService;
import com.sentinelpay.payments.service.HeldPaymentDecisionService;
import com.sentinelpay.payments.service.HeldPaymentQueryService;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import org.springframework.validation.annotation.Validated;

@RestController
@RequestMapping("/analyst")
@PreAuthorize("hasAuthority('ANALYST')")
@Validated
public class AnalystController {
    private final ReconciliationDetector reconciliationDetector;
    private final ReconciliationQueryService reconciliationQueryService;
    private final ReconciliationResolutionService reconciliationResolutionService;
    private final HeldPaymentQueryService heldPaymentQueries;
    private final HeldPaymentDecisionService heldPaymentDecisions;

    public AnalystController(
        ReconciliationDetector reconciliationDetector,
        ReconciliationQueryService reconciliationQueryService,
        ReconciliationResolutionService reconciliationResolutionService,
        HeldPaymentQueryService heldPaymentQueries,
        HeldPaymentDecisionService heldPaymentDecisions
    ) {
        this.reconciliationDetector = reconciliationDetector;
        this.reconciliationQueryService = reconciliationQueryService;
        this.reconciliationResolutionService = reconciliationResolutionService;
        this.heldPaymentQueries = heldPaymentQueries;
        this.heldPaymentDecisions = heldPaymentDecisions;
    }

    @GetMapping("/test")
    public String analystTest() {
        return "analyst access granted";
    }

    @GetMapping("/held-payments")
    public HeldPaymentPageResponse listHeldPayments(
        @RequestParam(required = false) String cursor,
        @RequestParam(defaultValue = "20") @Min(1) @Max(100) int limit,
        Authentication authentication
    ) {
        return heldPaymentQueries.list(cursor, limit, authentication.getName());
    }

    @GetMapping("/held-payments/{id}")
    public HeldPaymentResponse getHeldPayment(
        @PathVariable UUID id,
        Authentication authentication
    ) {
        return heldPaymentQueries.get(id, authentication.getName());
    }

    @PostMapping("/held-payments/{id}/decision")
    public HeldPaymentResponse decideHeldPayment(
        @PathVariable UUID id,
        @RequestHeader("Idempotency-Key")
        @NotBlank @Size(max = 128) String idempotencyKey,
        @Valid @RequestBody HeldPaymentDecisionRequest request,
        Authentication authentication
    ) {
        return heldPaymentDecisions.decide(
            id, request.expectedVersion(), request.action(), request.reason(),
            idempotencyKey, authentication.getName()
        );
    }

    @GetMapping("/held-payments/{id}/audit")
    public List<HeldPaymentAuditResponse> heldPaymentAudit(
        @PathVariable UUID id,
        Authentication authentication
    ) {
        return heldPaymentQueries.audit(id, authentication.getName());
    }

    @PostMapping("/reconciliation/runs")
    @ResponseStatus(HttpStatus.CREATED)
    public ReconciliationRunResponse runReconciliation(
        Authentication authentication
    ) {
        return ReconciliationRunResponse.from(
            reconciliationDetector.run(authentication.getName())
        );
    }

    @GetMapping("/reconciliation/discrepancies")
    public ReconciliationDiscrepancyPageResponse listDiscrepancies(
        @RequestParam(required = false) ReconciliationDiscrepancyStatus status,
        @RequestParam(required = false) String cursor,
        @RequestParam(defaultValue = "50") @Min(1) @Max(100) int limit,
        Authentication authentication
    ) {
        return reconciliationQueryService.list(
            status, cursor, limit, authentication.getName()
        );
    }

    @PostMapping("/reconciliation/discrepancies/{id}/resolve")
    public ReconciliationDiscrepancyResponse resolveDiscrepancy(
        @PathVariable UUID id,
        @RequestHeader("Idempotency-Key")
        @NotBlank @Size(max = 128) String idempotencyKey,
        @Valid @RequestBody ReconciliationResolutionRequest request,
        Authentication authentication
    ) {
        return ReconciliationDiscrepancyResponse.from(
            reconciliationResolutionService.resolve(
                id,
                request.expectedVersion(),
                request.action(),
                request.reason(),
                idempotencyKey,
                authentication.getName()
            )
        );
    }

    @GetMapping("/reconciliation/discrepancies/{id}/audit")
    public List<ReconciliationAuditResponse> auditTrail(
        @PathVariable UUID id,
        Authentication authentication
    ) {
        return reconciliationQueryService.auditTrail(id, authentication.getName());
    }
}
