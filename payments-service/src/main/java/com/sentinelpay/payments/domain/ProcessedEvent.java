package com.sentinelpay.payments.domain;

import java.time.LocalDateTime;
import java.util.UUID;


import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "processed_events")
public class ProcessedEvent {
    @Id
    @Column(name = "event_id")
    private UUID eventId;

    @Column(name = "processed_at", nullable = false)
    private LocalDateTime processedAt;

    public UUID getEventId() {
        return eventId;
    }

    public LocalDateTime getProcessedAt() {
        return processedAt;
    }

    public ProcessedEvent() {
    }

    public ProcessedEvent(UUID eventId) {
        this.eventId = eventId;
        this.processedAt = LocalDateTime.now();
    }

}
