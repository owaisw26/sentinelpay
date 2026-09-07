package com.sentinelpay.payments.controller.response;

import java.time.LocalDateTime;
import java.util.UUID;

import com.sentinelpay.payments.domain.ReconciliationRun;
import com.sentinelpay.payments.domain.ReconciliationRunStatus;

public record ReconciliationRunResponse(
    UUID runId,
    ReconciliationRunStatus status,
    LocalDateTime cutoffAt,
    LocalDateTime startedAt,
    LocalDateTime completedAt,
    int scannedCount,
    int discrepancyCount,
    String failureReason
) {
    public static ReconciliationRunResponse from(ReconciliationRun run) {
        return new ReconciliationRunResponse(
            run.getId(),
            run.getStatus(),
            run.getCutoffAt(),
            run.getStartedAt(),
            run.getCompletedAt(),
            run.getScannedCount(),
            run.getDiscrepancyCount(),
            run.getFailureReason()
        );
    }
}
