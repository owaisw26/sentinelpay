package com.sentinelpay.payments.domain;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

@Entity
@Table(name = "payment_reservations")
public class PaymentReservation {
    @Id
    @Column(name = "payment_id")
    private UUID paymentId;

    @ManyToOne
    @JoinColumn(name = "wallet_id", nullable = false)
    private Wallet wallet;

    @Column(name = "amount", nullable = false)
    private BigDecimal amount;

    @Column(name = "currency", nullable = false)
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private PaymentReservationStatus status;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    protected PaymentReservation() {}

    public PaymentReservation(Payment payment, Wallet wallet, LocalDateTime now) {
        this.paymentId = payment.getId();
        this.wallet = wallet;
        this.amount = payment.getAmount();
        this.currency = payment.getCurrency();
        this.status = PaymentReservationStatus.ACTIVE;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public UUID getPaymentId() {
        return paymentId;
    }

    public Wallet getWallet() {
        return wallet;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public String getCurrency() {
        return currency;
    }

    public PaymentReservationStatus getStatus() {
        return status;
    }

    public boolean isActive() {
        return status == PaymentReservationStatus.ACTIVE;
    }

    public void capture(LocalDateTime now) {
        requireActive();
        status = PaymentReservationStatus.CAPTURED;
        updatedAt = now;
    }

    public void release(LocalDateTime now) {
        requireActive();
        status = PaymentReservationStatus.RELEASED;
        updatedAt = now;
    }

    private void requireActive() {
        if (!isActive()) {
            throw new IllegalStateException("Payment reservation is already final");
        }
    }
}
