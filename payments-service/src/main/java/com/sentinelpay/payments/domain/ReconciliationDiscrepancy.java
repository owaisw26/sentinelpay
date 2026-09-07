package com.sentinelpay.payments.domain;

import java.time.LocalDateTime;
import java.util.UUID;

import com.sentinelpay.payments.provider.PaymentProviderLookupStatus;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

@Entity
@Table(name = "reconciliation_discrepancies")
public class ReconciliationDiscrepancy {
    @Id
    @Column(name = "id")
    private UUID id;

    @ManyToOne
    @JoinColumn(name = "run_id")
    private ReconciliationRun run;

    @Column(name = "payment_id", nullable = false)
    private UUID paymentId;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false)
    private ReconciliationDiscrepancyType type;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private ReconciliationDiscrepancyStatus status;

    @Enumerated(EnumType.STRING)
    @Column(name = "local_payment_status", nullable = false)
    private PaymentStatus localPaymentStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "provider_status", nullable = false)
    private PaymentProviderLookupStatus providerStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "recommended_action", nullable = false)
    private ReconciliationAction recommendedAction;

    @Column(name = "detected_at", nullable = false)
    private LocalDateTime detectedAt;

    @Column(name = "last_observed_at", nullable = false)
    private LocalDateTime lastObservedAt;

    @Column(name = "resolved_at")
    private LocalDateTime resolvedAt;

    @Column(name = "resolved_by")
    private String resolvedBy;

    @Enumerated(EnumType.STRING)
    @Column(name = "resolution_action")
    private ReconciliationAction resolutionAction;

    @Column(name = "resolution_reason")
    private String resolutionReason;

    @Column(name = "resolution_idempotency_key")
    private String resolutionIdempotencyKey;

    @Enumerated(EnumType.STRING)
    @Column(name = "resolution_provider_status")
    private PaymentProviderLookupStatus resolutionProviderStatus;

    @Version
    @Column(name = "version", nullable = false)
    private int version;

    protected ReconciliationDiscrepancy() {}

    public ReconciliationDiscrepancy(
        UUID id,
        ReconciliationRun run,
        UUID paymentId,
        ReconciliationDiscrepancyType type,
        PaymentStatus localPaymentStatus,
        PaymentProviderLookupStatus providerStatus,
        ReconciliationAction recommendedAction,
        LocalDateTime now
    ) {
        this.id = id;
        this.run = run;
        this.paymentId = paymentId;
        this.type = type;
        this.status = ReconciliationDiscrepancyStatus.OPEN;
        this.localPaymentStatus = localPaymentStatus;
        this.providerStatus = providerStatus;
        this.recommendedAction = recommendedAction;
        this.detectedAt = now;
        this.lastObservedAt = now;
    }

    public UUID getId() {
        return id;
    }

    public ReconciliationRun getRun() {
        return run;
    }

    public UUID getPaymentId() {
        return paymentId;
    }

    public ReconciliationDiscrepancyType getType() {
        return type;
    }

    public ReconciliationDiscrepancyStatus getStatus() {
        return status;
    }

    public PaymentStatus getLocalPaymentStatus() {
        return localPaymentStatus;
    }

    public PaymentProviderLookupStatus getProviderStatus() {
        return providerStatus;
    }

    public ReconciliationAction getRecommendedAction() {
        return recommendedAction;
    }

    public LocalDateTime getDetectedAt() {
        return detectedAt;
    }

    public LocalDateTime getLastObservedAt() {
        return lastObservedAt;
    }

    public LocalDateTime getResolvedAt() {
        return resolvedAt;
    }

    public String getResolvedBy() {
        return resolvedBy;
    }

    public ReconciliationAction getResolutionAction() {
        return resolutionAction;
    }

    public String getResolutionReason() {
        return resolutionReason;
    }

    public String getResolutionIdempotencyKey() {
        return resolutionIdempotencyKey;
    }

    public PaymentProviderLookupStatus getResolutionProviderStatus() {
        return resolutionProviderStatus;
    }

    public int getVersion() {
        return version;
    }

    public boolean isOpen() {
        return status == ReconciliationDiscrepancyStatus.OPEN;
    }

    public void observe(
        ReconciliationDiscrepancyType type,
        PaymentStatus localPaymentStatus,
        PaymentProviderLookupStatus providerStatus,
        ReconciliationAction recommendedAction,
        LocalDateTime now
    ) {
        if (!isOpen()) {
            throw new IllegalStateException("Only open discrepancies can be observed");
        }
        this.type = type;
        this.localPaymentStatus = localPaymentStatus;
        this.providerStatus = providerStatus;
        this.recommendedAction = recommendedAction;
        this.lastObservedAt = now;
    }

    public void resolve(
        String actorId,
        String idempotencyKey,
        ReconciliationAction action,
        String reason,
        PaymentProviderLookupStatus currentProviderStatus,
        LocalDateTime now
    ) {
        if (!isOpen()) {
            throw new IllegalStateException("Discrepancy is already final");
        }
        this.status = action == ReconciliationAction.IGNORE
            ? ReconciliationDiscrepancyStatus.IGNORED
            : ReconciliationDiscrepancyStatus.RESOLVED;
        this.resolvedAt = now;
        this.resolvedBy = actorId;
        this.resolutionAction = action;
        this.resolutionReason = reason;
        this.resolutionIdempotencyKey = idempotencyKey;
        this.resolutionProviderStatus = currentProviderStatus;
        this.providerStatus = currentProviderStatus;
        this.lastObservedAt = now;
    }
}
