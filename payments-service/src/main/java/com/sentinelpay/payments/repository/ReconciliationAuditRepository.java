package com.sentinelpay.payments.repository;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.sentinelpay.payments.domain.ReconciliationAuditRecord;

public interface ReconciliationAuditRepository
    extends JpaRepository<ReconciliationAuditRecord, UUID> {

    List<ReconciliationAuditRecord> findByDiscrepancyIdOrderByOccurredAtAscIdAsc(
        UUID discrepancyId
    );
}
