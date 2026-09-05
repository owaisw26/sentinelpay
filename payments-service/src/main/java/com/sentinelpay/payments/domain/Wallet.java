package com.sentinelpay.payments.domain;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

@Entity
@Table(name = "wallets")
public class Wallet {
    public Wallet() {}
    public Wallet(
        UUID id,
        User user,
        String currency,
        BigDecimal balance,
        LocalDateTime createdAt
    ) {
        this.id = id;
        this.user = user;
        this.currency = currency;
        this.balance = balance;
        this.reservedBalance = BigDecimal.ZERO;
        this.createdAt = createdAt;
    }

    @Id
    @Column(name = "id")
    private UUID id;

    @ManyToOne
    @JoinColumn(name = "user_id")
    private User user;

    @Column(name = "currency")
    private String currency;

    @Column(name = "balance")
    private BigDecimal balance;

    @Column(name = "reserved_balance", nullable = false)
    private BigDecimal reservedBalance = BigDecimal.ZERO;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    public UUID getId() {
        return id;
    }

    public User getUser() {
        return user;
    }

    public String getCurrency() {
        return currency;
    }

    public BigDecimal getBalance() {
        return balance;
    }

    public BigDecimal getReservedBalance() {
        return reservedBalance;
    }

    public BigDecimal getAvailableBalance() {
        return balance.subtract(reservedBalance);
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setBalance(BigDecimal balance) {
        this.balance = balance;
    }

    public void reserve(BigDecimal amount) {
        if (getAvailableBalance().compareTo(amount) < 0) {
            throw new IllegalStateException("Insufficient available balance");
        }
        reservedBalance = reservedBalance.add(amount);
    }

    public void captureReservation(BigDecimal amount) {
        if (reservedBalance.compareTo(amount) < 0) {
            throw new IllegalStateException("Reservation exceeds reserved balance");
        }
        reservedBalance = reservedBalance.subtract(amount);
        balance = balance.subtract(amount);
    }

    public void releaseReservation(BigDecimal amount) {
        if (reservedBalance.compareTo(amount) < 0) {
            throw new IllegalStateException("Reservation exceeds reserved balance");
        }
        reservedBalance = reservedBalance.subtract(amount);
    }
}
