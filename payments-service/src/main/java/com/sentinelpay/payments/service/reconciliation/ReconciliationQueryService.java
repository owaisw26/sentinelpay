package com.sentinelpay.payments.service.reconciliation;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import com.sentinelpay.payments.controller.response.ReconciliationAuditResponse;
import com.sentinelpay.payments.controller.response.ReconciliationDiscrepancyPageResponse;
import com.sentinelpay.payments.controller.response.ReconciliationDiscrepancyResponse;
import com.sentinelpay.payments.domain.ReconciliationAuditRecord;
import com.sentinelpay.payments.domain.ReconciliationDiscrepancy;
import com.sentinelpay.payments.domain.ReconciliationDiscrepancyStatus;
import com.sentinelpay.payments.exception.InvalidReconciliationRequestException;
import com.sentinelpay.payments.exception.ReconciliationNotFoundException;
import com.sentinelpay.payments.repository.ReconciliationAuditRepository;
import com.sentinelpay.payments.repository.ReconciliationDiscrepancyRepository;

import jakarta.transaction.Transactional;

@Service
public class ReconciliationQueryService {
    private final ReconciliationDiscrepancyRepository discrepancyRepository;
    private final ReconciliationAuditRepository auditRepository;

    public ReconciliationQueryService(
        ReconciliationDiscrepancyRepository discrepancyRepository,
        ReconciliationAuditRepository auditRepository
    ) {
        this.discrepancyRepository = discrepancyRepository;
        this.auditRepository = auditRepository;
    }

    @Transactional
    public ReconciliationDiscrepancyPageResponse list(
        ReconciliationDiscrepancyStatus status,
        String encodedCursor,
        int limit,
        String actorId
    ) {
        Cursor cursor = decode(encodedCursor);
        PageRequest page = PageRequest.of(0, limit + 1);
        List<ReconciliationDiscrepancy> found;
        if (cursor == null && status == null) {
            found = discrepancyRepository.findAllByOrderByDetectedAtDescIdDesc(page);
        } else if (cursor == null) {
            found = discrepancyRepository.findByStatusOrderByDetectedAtDescIdDesc(
                status, page
            );
        } else if (status == null) {
            found = discrepancyRepository.findAllAfterCursor(
                cursor.detectedAt(), cursor.id(), page
            );
        } else {
            found = discrepancyRepository.findByStatusAfterCursor(
                status, cursor.detectedAt(), cursor.id(), page
            );
        }

        boolean hasNext = found.size() > limit;
        List<ReconciliationDiscrepancy> pageItems = new ArrayList<>(
            found.subList(0, Math.min(limit, found.size()))
        );
        String nextCursor = hasNext
            ? encode(pageItems.getLast())
            : null;
        auditRepository.save(new ReconciliationAuditRecord(
            UUID.randomUUID(), null, null, null, "DISCREPANCIES_READ",
            actorId,
            "status=" + (status == null ? "ALL" : status)
                + ",resultCount=" + pageItems.size(),
            LocalDateTime.now()
        ));
        return new ReconciliationDiscrepancyPageResponse(
            pageItems.stream()
                .map(ReconciliationDiscrepancyResponse::from)
                .toList(),
            nextCursor
        );
    }

    @Transactional
    public List<ReconciliationAuditResponse> auditTrail(
        UUID discrepancyId,
        String actorId
    ) {
        if (!discrepancyRepository.existsById(discrepancyId)) {
            throw new ReconciliationNotFoundException();
        }
        List<ReconciliationAuditResponse> result = auditRepository
            .findByDiscrepancyIdOrderByOccurredAtAscIdAsc(discrepancyId)
            .stream()
            .map(ReconciliationAuditResponse::from)
            .toList();
        auditRepository.save(new ReconciliationAuditRecord(
            UUID.randomUUID(), null, discrepancyId, null, "AUDIT_TRAIL_READ",
            actorId, "resultCount=" + result.size(), LocalDateTime.now()
        ));
        return result;
    }

    private String encode(ReconciliationDiscrepancy discrepancy) {
        String value = discrepancy.getDetectedAt() + "|" + discrepancy.getId();
        return Base64.getUrlEncoder().withoutPadding().encodeToString(
            value.getBytes(StandardCharsets.UTF_8)
        );
    }

    private Cursor decode(String encoded) {
        if (encoded == null || encoded.isBlank()) {
            return null;
        }
        try {
            String decoded = new String(
                Base64.getUrlDecoder().decode(encoded),
                StandardCharsets.UTF_8
            );
            String[] parts = decoded.split("\\|", 2);
            if (parts.length != 2) {
                throw new IllegalArgumentException();
            }
            return new Cursor(
                LocalDateTime.parse(parts[0]),
                UUID.fromString(parts[1])
            );
        } catch (RuntimeException invalid) {
            throw new InvalidReconciliationRequestException(
                "Reconciliation cursor is invalid"
            );
        }
    }

    private record Cursor(LocalDateTime detectedAt, UUID id) {}
}
