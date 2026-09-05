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
@Table(name = "provider_attempts")
public class ProviderAttempt {
    @Id
    @Column(name = "payment_id")
    private UUID paymentId;

    @Column(name = "event_id", nullable = false, unique = true)
    private UUID eventId;

    @Column(name = "idempotency_key", nullable = false, unique = true)
    private UUID idempotencyKey;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private ProviderAttemptStatus status;

    @Column(name = "lease_token")
    private UUID leaseToken;

    @Column(name = "lease_until")
    private LocalDateTime leaseUntil;

    @Column(name = "attempt_count", nullable = false)
    private int attemptCount;

    @Column(name = "provider_payment_id")
    private String providerPaymentId;

    @Column(name = "last_error")
    private String lastError;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    protected ProviderAttempt() {}

    public ProviderAttempt(
        UUID paymentId,
        UUID eventId,
        UUID leaseToken,
        LocalDateTime leaseUntil,
        LocalDateTime now
    ) {
        this.paymentId = paymentId;
        this.eventId = eventId;
        this.idempotencyKey = paymentId;
        this.status = ProviderAttemptStatus.IN_FLIGHT;
        this.leaseToken = leaseToken;
        this.leaseUntil = leaseUntil;
        this.attemptCount = 1;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public UUID getPaymentId() {
        return paymentId;
    }

    public UUID getIdempotencyKey() {
        return idempotencyKey;
    }

    public boolean isCompleted() {
        return status == ProviderAttemptStatus.COMPLETED;
    }

    public boolean hasActiveLease(LocalDateTime now) {
        return leaseToken != null && leaseUntil != null && leaseUntil.isAfter(now);
    }

    public void claim(UUID token, LocalDateTime until, LocalDateTime now) {
        if (isCompleted() || hasActiveLease(now)) {
            throw new IllegalStateException("Provider attempt cannot be claimed");
        }
        leaseToken = token;
        leaseUntil = until;
        attemptCount++;
        lastError = null;
        updatedAt = now;
    }

    public boolean ownsLease(UUID token) {
        return token != null && token.equals(leaseToken);
    }

    public void complete(UUID token, String providerPaymentId, LocalDateTime now) {
        requireLease(token);
        this.status = ProviderAttemptStatus.COMPLETED;
        this.providerPaymentId = providerPaymentId;
        this.leaseToken = null;
        this.leaseUntil = null;
        this.lastError = null;
        this.updatedAt = now;
    }

    public void releaseForRetry(UUID token, String error, LocalDateTime now) {
        requireLease(token);
        this.leaseToken = null;
        this.leaseUntil = now;
        this.lastError = error;
        this.updatedAt = now;
    }

    private void requireLease(UUID token) {
        if (!ownsLease(token)) {
            throw new IllegalStateException("Provider attempt lease is not owned by this worker");
        }
    }
}
