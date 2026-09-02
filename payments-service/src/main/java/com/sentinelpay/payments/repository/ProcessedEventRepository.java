package com.sentinelpay.payments.repository;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.sentinelpay.payments.domain.ProcessedEvent;

public interface ProcessedEventRepository extends JpaRepository<ProcessedEvent, UUID>{
    @Modifying
    @Query(value = """
        INSERT INTO processed_events(event_id, processed_at)
        VALUES (:eventId, CURRENT_TIMESTAMP)
        ON CONFLICT (event_id) DO NOTHING
        """, nativeQuery = true)
    int claimEvent(@Param("eventId") UUID eventId);
}
