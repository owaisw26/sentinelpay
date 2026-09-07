package com.sentinelpay.payments.repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.sentinelpay.payments.domain.ReconciliationDiscrepancy;
import com.sentinelpay.payments.domain.ReconciliationDiscrepancyStatus;

import jakarta.persistence.LockModeType;

public interface ReconciliationDiscrepancyRepository
    extends JpaRepository<ReconciliationDiscrepancy, UUID> {

    Optional<ReconciliationDiscrepancy> findByPaymentIdAndStatus(
        UUID paymentId,
        ReconciliationDiscrepancyStatus status
    );

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
        select d from ReconciliationDiscrepancy d
        where d.paymentId = :paymentId
          and d.status = com.sentinelpay.payments.domain.ReconciliationDiscrepancyStatus.OPEN
        """)
    Optional<ReconciliationDiscrepancy> findOpenByPaymentIdForUpdate(
        @Param("paymentId") UUID paymentId
    );

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
        INSERT INTO reconciliation_discrepancies(
            id, run_id, payment_id, type, status, local_payment_status,
            provider_status, recommended_action, detected_at,
            last_observed_at, version
        ) VALUES (
            :id, :runId, :paymentId, :type, 'OPEN', :localStatus,
            :providerStatus, :recommendedAction, :now, :now, 0
        )
        ON CONFLICT (payment_id) WHERE status = 'OPEN'
        DO UPDATE SET
            type = EXCLUDED.type,
            local_payment_status = EXCLUDED.local_payment_status,
            provider_status = EXCLUDED.provider_status,
            recommended_action = EXCLUDED.recommended_action,
            last_observed_at = EXCLUDED.last_observed_at,
            version = reconciliation_discrepancies.version + 1
        """, nativeQuery = true)
    int upsertOpen(
        @Param("id") UUID id,
        @Param("runId") UUID runId,
        @Param("paymentId") UUID paymentId,
        @Param("type") String type,
        @Param("localStatus") String localStatus,
        @Param("providerStatus") String providerStatus,
        @Param("recommendedAction") String recommendedAction,
        @Param("now") LocalDateTime now
    );

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select d from ReconciliationDiscrepancy d where d.id = :id")
    Optional<ReconciliationDiscrepancy> findByIdForUpdate(@Param("id") UUID id);

    Optional<ReconciliationDiscrepancy>
        findByResolvedByAndResolutionIdempotencyKey(
            String resolvedBy,
            String resolutionIdempotencyKey
        );

    List<ReconciliationDiscrepancy> findAllByOrderByDetectedAtDescIdDesc(
        Pageable pageable
    );

    List<ReconciliationDiscrepancy>
        findByStatusOrderByDetectedAtDescIdDesc(
            ReconciliationDiscrepancyStatus status,
            Pageable pageable
        );

    @Query("""
        select d from ReconciliationDiscrepancy d
        where d.detectedAt < :cursorDetectedAt
           or (d.detectedAt = :cursorDetectedAt and d.id < :cursorId)
        order by d.detectedAt desc, d.id desc
        """)
    List<ReconciliationDiscrepancy> findAllAfterCursor(
        @Param("cursorDetectedAt") LocalDateTime cursorDetectedAt,
        @Param("cursorId") UUID cursorId,
        Pageable pageable
    );

    @Query("""
        select d from ReconciliationDiscrepancy d
        where d.status = :status
          and (d.detectedAt < :cursorDetectedAt
            or (d.detectedAt = :cursorDetectedAt and d.id < :cursorId))
        order by d.detectedAt desc, d.id desc
        """)
    List<ReconciliationDiscrepancy> findByStatusAfterCursor(
        @Param("status") ReconciliationDiscrepancyStatus status,
        @Param("cursorDetectedAt") LocalDateTime cursorDetectedAt,
        @Param("cursorId") UUID cursorId,
        Pageable pageable
    );
}
