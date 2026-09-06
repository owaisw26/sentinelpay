package com.sentinelpay.payments.domain;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.sentinelpay.payments.exception.InvalidPaymentTransition;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

@Entity
@Table(name = "payments")
public class Payment {
    private static final Map<PaymentStatus, List<PaymentStatus>> validPaymentStates = Map.of(
        PaymentStatus.CREATED, List.of(PaymentStatus.SCREENING), PaymentStatus.SCREENING, List.of(PaymentStatus.APPROVED, PaymentStatus.BLOCKED), PaymentStatus.APPROVED, List.of(PaymentStatus.PROCESSING), PaymentStatus.PROCESSING, List.of(PaymentStatus.SETTLED, PaymentStatus.FAILED)
    );

    public Payment() {}

    public Payment(UUID id, int version, Wallet senderWallet, Wallet receiverWallet, BigDecimal amount, String currency, String reference, PaymentStatus status, String idempotencyKey, String requestHash, LocalDateTime createdAt, LocalDateTime updatedAt) {
        this.id = id;
        this.version = version;
        this.senderWallet = senderWallet;
        this.receiverWallet = receiverWallet;
        this.amount = amount;
        this.currency = currency;
        this.reference = reference;
        this.status = status;
        this.idempotencyKey = idempotencyKey;
        this.requestHash = requestHash;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    @Id
    @Column(name="id")
    private UUID id;

    @Version
    @Column(name = "version", nullable = false)
    private int version;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name="sender_wallet_id", nullable =  false)
    private Wallet senderWallet;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name="receiver_wallet_id", nullable =false)
    private Wallet receiverWallet;

    @Column(name = "amount", nullable = false)
    private BigDecimal amount;

    @Column(name = "currency", nullable =  false)
    private String currency;

    @Column(name = "reference", nullable = false)
    private String reference;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private PaymentStatus status;

    @Column(name = "idempotency_key", nullable = false)
    private String idempotencyKey;

    @Column(name = "request_hash", nullable = false)
    private String requestHash;

    @Column(name = "provider_payment_id", unique = true)
    private String providerPaymentId;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    public UUID getId() {
        return id;
    }

    public int getVersion() {
        return version;
    }

    public Wallet getSenderWallet() {
        return senderWallet;
    }

    public Wallet getReceiverWallet() {
        return receiverWallet;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public String getCurrency() {
        return currency;
    }

    public String getReference() {
        return reference;
    }

    public PaymentStatus getStatus() {
        return status;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public String getRequestHash() {
        return requestHash;
    }

    public String getProviderPaymentId() {
        return providerPaymentId;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void transitionTo(PaymentStatus newStatus) {
        List<PaymentStatus> possibleStates = validPaymentStates.get(this.status);

        if (possibleStates.contains(newStatus)) {
            this.status = newStatus;
            this.updatedAt = LocalDateTime.now();
        } else {
            throw new InvalidPaymentTransition(this.status.toString(), newStatus.toString());
        }
    }

    public void assignProviderPaymentId(String providerPaymentId) {
        if (this.providerPaymentId != null &&
            !this.providerPaymentId.equals(providerPaymentId)) {
            throw new IllegalStateException(
                "Payment already has a different provider payment ID"
            );
        }

        this.providerPaymentId = providerPaymentId;
    }
}
