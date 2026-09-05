package com.sentinelpay.payments.repository;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.sentinelpay.payments.domain.OutboxEvent;

public interface OutboxEventRepository extends
                                       JpaRepository<OutboxEvent, UUID> {
    @Query("select e from OutboxEvent e where e.aggregateId = :aggregateId")
    List<OutboxEvent> findOutboxEventsByAggregateId(@Param("aggregateId")
                                                        UUID aggregateId);

    @Query("select e from OutboxEvent e where e.publishedAt is null")
    List<OutboxEvent> findByPublishedAtIsNull();

    @Query(value = """
        SELECT *
        FROM outbox_events
        WHERE published_at IS NULL
          AND next_attempt_at <= :now
          AND (lease_until IS NULL OR lease_until <= :now)
        ORDER BY created_at, id
        FOR UPDATE SKIP LOCKED
        LIMIT :batchSize
        """, nativeQuery = true)
    List<OutboxEvent> lockClaimableBatch(
        @Param("now") java.time.LocalDateTime now,
        @Param("batchSize") int batchSize
    );

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
        UPDATE OutboxEvent e
        SET e.publishedAt = :publishedAt,
            e.leaseToken = null,
            e.leaseUntil = null,
            e.lastError = null
        WHERE e.id = :eventId
          AND e.leaseToken = :leaseToken
          AND e.publishedAt IS NULL
        """)
    int completeClaim(
        @Param("eventId") UUID eventId,
        @Param("leaseToken") UUID leaseToken,
        @Param("publishedAt") java.time.LocalDateTime publishedAt
    );

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
        UPDATE OutboxEvent e
        SET e.leaseToken = null,
            e.leaseUntil = null,
            e.nextAttemptAt = :nextAttemptAt,
            e.lastError = :lastError
        WHERE e.id = :eventId
          AND e.leaseToken = :leaseToken
          AND e.publishedAt IS NULL
        """)
    int failClaim(
        @Param("eventId") UUID eventId,
        @Param("leaseToken") UUID leaseToken,
        @Param("nextAttemptAt") java.time.LocalDateTime nextAttemptAt,
        @Param("lastError") String lastError
    );

}
