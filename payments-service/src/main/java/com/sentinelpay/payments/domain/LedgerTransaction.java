package com.sentinelpay.payments.domain;

import java.time.LocalDateTime;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "ledger_transactions")
public class LedgerTransaction {
    public LedgerTransaction() {

    }

    public LedgerTransaction(UUID id, 
        String reference,
        String type,
        LocalDateTime createdAt
    ) {
        this(id, reference, type, createdAt, null);
    }

    public LedgerTransaction(
        UUID id,
        String reference,
        String type,
        LocalDateTime createdAt,
        UUID paymentId
    ) {
        this.id = id;
        this.reference = reference;
        this.type = type;
        this.createdAt = createdAt;
        this.paymentId = paymentId;
    }

    @Id
    @Column(name = "id")
    private UUID id;

    @Column(name = "reference", nullable = false)
    private String reference; 

    @Column(name = "type", nullable = false)
    private String type;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "payment_id", unique = true)
    private UUID paymentId;

    public UUID getId() {
        return id;
    }

    public UUID getPaymentId() {
        return paymentId;
    }
}
