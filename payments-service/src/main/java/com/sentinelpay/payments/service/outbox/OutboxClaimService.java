package com.sentinelpay.payments.service.outbox;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.sentinelpay.payments.domain.OutboxEvent;
import com.sentinelpay.payments.repository.OutboxEventRepository;

@Service
public class OutboxClaimService {
    private final OutboxEventRepository outboxEventRepository;

    public OutboxClaimService(OutboxEventRepository outboxEventRepository) {
        this.outboxEventRepository = outboxEventRepository;
    }

    @Transactional
    public List<OutboxEvent> claimBatch(int batchSize, long leaseSeconds) {
        LocalDateTime now = LocalDateTime.now();
        List<OutboxEvent> events = outboxEventRepository.lockClaimableBatch(
            now,
            batchSize
        );
        for (OutboxEvent event : events) {
            event.claim(
                UUID.randomUUID(),
                now.plusSeconds(leaseSeconds),
                now
            );
        }
        return List.copyOf(events);
    }

    @Transactional
    public void complete(UUID eventId, UUID leaseToken) {
        int completed = outboxEventRepository.completeClaim(
            eventId,
            leaseToken,
            LocalDateTime.now()
        );
        if (completed != 1) {
            throw new IllegalStateException("Outbox event lease was lost");
        }
    }

    @Transactional
    public void fail(
        UUID eventId,
        UUID leaseToken,
        long retryDelayMillis,
        RuntimeException failure
    ) {
        String message = failure.getMessage();
        if (message == null || message.isBlank()) {
            message = failure.getClass().getSimpleName();
        }
        if (message.length() > 1000) {
            message = message.substring(0, 1000);
        }
        int failed = outboxEventRepository.failClaim(
            eventId,
            leaseToken,
            LocalDateTime.now().plusNanos(retryDelayMillis * 1_000_000),
            message
        );
        if (failed != 1) {
            throw new IllegalStateException("Outbox event lease was lost");
        }
    }
}
