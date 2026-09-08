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
@Table(name = "payee_checks")
public class PayeeCheck {
    @Id
    @Column(name = "id")
    private UUID id;

    @Column(name = "requester_user_id", nullable = false)
    private UUID requesterUserId;

    @Column(name = "receiver_wallet_id", nullable = false)
    private UUID receiverWalletId;

    @Column(name = "registry_version", nullable = false)
    private int registryVersion;

    @Column(name = "supplied_name_hash", nullable = false)
    private String suppliedNameHash;

    @Enumerated(EnumType.STRING)
    @Column(name = "outcome", nullable = false)
    private PayeeCheckOutcome outcome;

    @Enumerated(EnumType.STRING)
    @Column(name = "reason_code", nullable = false)
    private PayeeCheckReason reasonCode;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;

    @Column(name = "consumed_at")
    private LocalDateTime consumedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "verification_source", nullable = false)
    private PayeeCheckVerificationSource verificationSource;

    protected PayeeCheck() {}

    public PayeeCheck(UUID id, UUID requesterUserId, UUID receiverWalletId,
        int registryVersion, String suppliedNameHash,
        PayeeCheckOutcome outcome, PayeeCheckReason reasonCode,
        LocalDateTime createdAt, LocalDateTime expiresAt,
        PayeeCheckVerificationSource verificationSource) {
        this.id = id;
        this.requesterUserId = requesterUserId;
        this.receiverWalletId = receiverWalletId;
        this.registryVersion = registryVersion;
        this.suppliedNameHash = suppliedNameHash;
        this.outcome = outcome;
        this.reasonCode = reasonCode;
        this.createdAt = createdAt;
        this.expiresAt = expiresAt;
        this.verificationSource = verificationSource;
    }

    public UUID getId() { return id; }
    public UUID getRequesterUserId() { return requesterUserId; }
    public UUID getReceiverWalletId() { return receiverWalletId; }
    public int getRegistryVersion() { return registryVersion; }
    public String getSuppliedNameHash() { return suppliedNameHash; }
    public PayeeCheckOutcome getOutcome() { return outcome; }
    public PayeeCheckReason getReasonCode() { return reasonCode; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getExpiresAt() { return expiresAt; }
    public LocalDateTime getConsumedAt() { return consumedAt; }
    public PayeeCheckVerificationSource getVerificationSource() {
        return verificationSource;
    }

    public boolean isExpiredAt(LocalDateTime now) {
        return !expiresAt.isAfter(now);
    }

    public boolean isMismatch() {
        return outcome != PayeeCheckOutcome.MATCH;
    }

    public void consumeMismatch(LocalDateTime now) {
        if (!isMismatch() || consumedAt != null) {
            throw new IllegalStateException("Payee check cannot be consumed");
        }
        consumedAt = now;
    }
}
