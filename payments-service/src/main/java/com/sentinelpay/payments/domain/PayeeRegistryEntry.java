package com.sentinelpay.payments.domain;

import java.time.LocalDateTime;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "payee_registry_entries")
public class PayeeRegistryEntry {
    @Id
    @Column(name = "id")
    private UUID id;

    @Column(name = "receiver_wallet_id", nullable = false)
    private UUID receiverWalletId;

    @Column(name = "registry_version", nullable = false)
    private int registryVersion;

    @Column(name = "legal_name", nullable = false)
    private String legalName;

    @Column(name = "active", nullable = false)
    private boolean active;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    protected PayeeRegistryEntry() {}

    public PayeeRegistryEntry(UUID id, UUID receiverWalletId,
        int registryVersion, String legalName, boolean active,
        LocalDateTime createdAt) {
        this.id = id;
        this.receiverWalletId = receiverWalletId;
        this.registryVersion = registryVersion;
        this.legalName = legalName;
        this.active = active;
        this.createdAt = createdAt;
    }

    public UUID getId() { return id; }
    public UUID getReceiverWalletId() { return receiverWalletId; }
    public int getRegistryVersion() { return registryVersion; }
    public String getLegalName() { return legalName; }
    public boolean isActive() { return active; }
    public LocalDateTime getCreatedAt() { return createdAt; }

    public void deactivate() {
        this.active = false;
    }
}
