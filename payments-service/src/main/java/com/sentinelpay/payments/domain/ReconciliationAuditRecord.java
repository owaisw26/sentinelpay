package com.sentinelpay.payments.domain;

import java.time.LocalDateTime;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "reconciliation_audit_records")
public class ReconciliationAuditRecord {
    @Id
    @Column(name = "id")
    private UUID id;

    @Column(name = "run_id")
    private UUID runId;

    @Column(name = "discrepancy_id")
    private UUID discrepancyId;

    @Column(name = "payment_id")
    private UUID paymentId;

    @Column(name = "action", nullable = false)
    private String action;

    @Column(name = "actor_id", nullable = false)
    private String actorId;

    @Column(name = "details", nullable = false)
    private String details;

    @Column(name = "occurred_at", nullable = false)
    private LocalDateTime occurredAt;

    protected ReconciliationAuditRecord() {}

    public ReconciliationAuditRecord(
        UUID id,
        UUID runId,
        UUID discrepancyId,
        UUID paymentId,
        String action,
        String actorId,
        String details,
        LocalDateTime occurredAt
    ) {
        this.id = id;
        this.runId = runId;
        this.discrepancyId = discrepancyId;
        this.paymentId = paymentId;
        this.action = action;
        this.actorId = actorId;
        this.details = details;
        this.occurredAt = occurredAt;
    }

    public UUID getId() { return id; }
    public UUID getRunId() { return runId; }
    public UUID getDiscrepancyId() { return discrepancyId; }
    public UUID getPaymentId() { return paymentId; }
    public String getAction() { return action; }
    public String getActorId() { return actorId; }
    public String getDetails() { return details; }
    public LocalDateTime getOccurredAt() { return occurredAt; }
}
