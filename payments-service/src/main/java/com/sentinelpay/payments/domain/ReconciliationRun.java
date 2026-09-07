package com.sentinelpay.payments.domain;

import java.time.LocalDateTime;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "reconciliation_runs")
public class ReconciliationRun {
    @Id
    @Column(name = "id")
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private ReconciliationRunStatus status;

    @Column(name = "cutoff_at", nullable = false)
    private LocalDateTime cutoffAt;

    @Column(name = "started_at", nullable = false)
    private LocalDateTime startedAt;

    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    @Column(name = "scanned_count", nullable = false)
    private int scannedCount;

    @Column(name = "discrepancy_count", nullable = false)
    private int discrepancyCount;

    @Column(name = "failure_reason")
    private String failureReason;

    protected ReconciliationRun() {}

    public ReconciliationRun(UUID id, LocalDateTime cutoffAt, LocalDateTime now) {
        this.id = id;
        this.status = ReconciliationRunStatus.RUNNING;
        this.cutoffAt = cutoffAt;
        this.startedAt = now;
    }

    public UUID getId() {
        return id;
    }

    public ReconciliationRunStatus getStatus() {
        return status;
    }

    public LocalDateTime getCutoffAt() {
        return cutoffAt;
    }

    public LocalDateTime getStartedAt() {
        return startedAt;
    }

    public LocalDateTime getCompletedAt() {
        return completedAt;
    }

    public int getScannedCount() {
        return scannedCount;
    }

    public int getDiscrepancyCount() {
        return discrepancyCount;
    }

    public String getFailureReason() {
        return failureReason;
    }

    public void complete(int scannedCount, int discrepancyCount, LocalDateTime now) {
        this.status = ReconciliationRunStatus.COMPLETED;
        this.scannedCount = scannedCount;
        this.discrepancyCount = discrepancyCount;
        this.completedAt = now;
        this.failureReason = null;
    }

    public void fail(int scannedCount, int discrepancyCount, String reason,
        LocalDateTime now) {
        this.status = ReconciliationRunStatus.FAILED;
        this.scannedCount = scannedCount;
        this.discrepancyCount = discrepancyCount;
        this.completedAt = now;
        this.failureReason = reason == null ? "Reconciliation failed"
            : reason.substring(0, Math.min(reason.length(), 1000));
    }
}
