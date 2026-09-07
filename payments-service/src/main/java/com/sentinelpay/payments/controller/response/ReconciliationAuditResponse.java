package com.sentinelpay.payments.controller.response;

import java.time.LocalDateTime;
import java.util.UUID;

import com.sentinelpay.payments.domain.ReconciliationAuditRecord;

public record ReconciliationAuditResponse(
    UUID auditId,
    UUID runId,
    UUID discrepancyId,
    UUID paymentId,
    String action,
    String actorId,
    String details,
    LocalDateTime occurredAt
) {
    public static ReconciliationAuditResponse from(
        ReconciliationAuditRecord record
    ) {
        return new ReconciliationAuditResponse(
            record.getId(), record.getRunId(), record.getDiscrepancyId(),
            record.getPaymentId(), record.getAction(), record.getActorId(),
            record.getDetails(), record.getOccurredAt()
        );
    }
}
