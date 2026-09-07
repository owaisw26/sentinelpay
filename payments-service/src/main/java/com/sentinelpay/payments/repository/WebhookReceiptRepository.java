package com.sentinelpay.payments.repository;

import java.time.LocalDateTime;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.sentinelpay.payments.domain.WebhookReceipt;

public interface WebhookReceiptRepository
    extends JpaRepository<WebhookReceipt, UUID> {

    @Modifying
    @Query(value = """
        INSERT INTO webhook_receipts(
            provider_event_id,
            provider_payment_id,
            event_status,
            payload_sha256,
            received_at,
            last_received_at
        ) VALUES (
            :eventId,
            :providerPaymentId,
            :eventStatus,
            :payloadSha256,
            :receivedAt,
            :receivedAt
        )
        ON CONFLICT (provider_event_id) DO NOTHING
        """, nativeQuery = true)
    int claim(
        @Param("eventId") UUID eventId,
        @Param("providerPaymentId") String providerPaymentId,
        @Param("eventStatus") String eventStatus,
        @Param("payloadSha256") String payloadSha256,
        @Param("receivedAt") LocalDateTime receivedAt
    );

    @Modifying
    @Query("""
        update WebhookReceipt receipt
        set receipt.processedAt = :processedAt
        where receipt.providerEventId = :eventId
        """)
    int markProcessed(
        @Param("eventId") UUID eventId,
        @Param("processedAt") LocalDateTime processedAt
    );

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
        update WebhookReceipt receipt
        set receipt.deliveryCount = receipt.deliveryCount + 1,
            receipt.lastReceivedAt = :receivedAt
        where receipt.providerEventId = :eventId
        """)
    int recordDuplicateDelivery(
        @Param("eventId") UUID eventId,
        @Param("receivedAt") LocalDateTime receivedAt
    );
}
