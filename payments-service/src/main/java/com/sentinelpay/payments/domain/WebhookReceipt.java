package com.sentinelpay.payments.domain;

import java.time.LocalDateTime;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "webhook_receipts")
public class WebhookReceipt {
    @Id
    @Column(name = "provider_event_id")
    private UUID providerEventId;

    @Column(name = "provider_payment_id", nullable = false)
    private String providerPaymentId;

    @Column(name = "event_status", nullable = false)
    private String eventStatus;

    @Column(name = "payload_sha256", nullable = false)
    private String payloadSha256;

    @Column(name = "received_at", nullable = false)
    private LocalDateTime receivedAt;

    @Column(name = "processed_at")
    private LocalDateTime processedAt;

    @Column(name = "delivery_count", nullable = false)
    private int deliveryCount;

    @Column(name = "last_received_at", nullable = false)
    private LocalDateTime lastReceivedAt;

    protected WebhookReceipt() {}

    public UUID getProviderEventId() {
        return providerEventId;
    }

    public String getProviderPaymentId() {
        return providerPaymentId;
    }

    public String getEventStatus() {
        return eventStatus;
    }

    public String getPayloadSha256() {
        return payloadSha256;
    }

    public LocalDateTime getReceivedAt() {
        return receivedAt;
    }

    public LocalDateTime getProcessedAt() {
        return processedAt;
    }

    public int getDeliveryCount() {
        return deliveryCount;
    }

    public LocalDateTime getLastReceivedAt() {
        return lastReceivedAt;
    }
}
