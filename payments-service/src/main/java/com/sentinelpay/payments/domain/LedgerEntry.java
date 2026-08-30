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
@Table(name = "ledger_entries")
public class LedgerEntry {
    public LedgerEntry() {

    }

    public LedgerEntry(
        UUID id,
        LedgerTransaction ledgerTransaction,
        Wallet wallet,
        BigDecimal amount,
        LocalDateTime createdAt) {
    this.id = id;
    this.ledgerTransaction = ledgerTransaction;
    this.wallet = wallet;
    this.amount = amount;
    this.createdAt = createdAt;
}

    @Id
    @Column(name = "id")
    private UUID id;

    @ManyToOne
    @JoinColumn(name = "ledger_transaction_id", nullable =  false)
    private LedgerTransaction ledgerTransaction;

    @ManyToOne
    @JoinColumn(name = "wallet_id", nullable =  false)
    private Wallet wallet;

    @Column(name = "amount", nullable = false)
    private BigDecimal amount;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    public UUID getId() {
        return id;
    }

    public LedgerTransaction getLedgerTransaction() {
        return ledgerTransaction;
    }

    public Wallet getWallet() {
        return wallet;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }
}
