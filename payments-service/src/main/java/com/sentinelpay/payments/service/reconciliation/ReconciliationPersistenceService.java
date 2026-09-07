package com.sentinelpay.payments.service.reconciliation;

import java.time.LocalDateTime;
import java.util.UUID;

import org.springframework.stereotype.Service;

import com.sentinelpay.payments.domain.PaymentStatus;
import com.sentinelpay.payments.domain.ReconciliationAction;
import com.sentinelpay.payments.domain.ReconciliationAuditRecord;
import com.sentinelpay.payments.domain.ReconciliationDiscrepancy;
import com.sentinelpay.payments.domain.ReconciliationDiscrepancyStatus;
import com.sentinelpay.payments.domain.ReconciliationDiscrepancyType;
import com.sentinelpay.payments.domain.ReconciliationRun;
import com.sentinelpay.payments.provider.PaymentProviderLookupStatus;
import com.sentinelpay.payments.repository.ReconciliationAuditRepository;
import com.sentinelpay.payments.repository.ReconciliationDiscrepancyRepository;
import com.sentinelpay.payments.repository.ReconciliationRunRepository;

import jakarta.transaction.Transactional;

@Service
public class ReconciliationPersistenceService {
    private final ReconciliationRunRepository runRepository;
    private final ReconciliationDiscrepancyRepository discrepancyRepository;
    private final ReconciliationAuditRepository auditRepository;

    public ReconciliationPersistenceService(
        ReconciliationRunRepository runRepository,
        ReconciliationDiscrepancyRepository discrepancyRepository,
        ReconciliationAuditRepository auditRepository
    ) {
        this.runRepository = runRepository;
        this.discrepancyRepository = discrepancyRepository;
        this.auditRepository = auditRepository;
    }

    @Transactional
    public ReconciliationRun startRun(LocalDateTime cutoff, String actorId) {
        LocalDateTime now = LocalDateTime.now();
        ReconciliationRun run = runRepository.save(
            new ReconciliationRun(UUID.randomUUID(), cutoff, now)
        );
        auditRepository.save(audit(
            run.getId(), null, null, "RUN_STARTED", actorId,
            "cutoff=" + cutoff, now
        ));
        return run;
    }

    @Transactional
    public ReconciliationDiscrepancy recordObservation(
        UUID runId,
        UUID paymentId,
        PaymentStatus localStatus,
        PaymentProviderLookupStatus providerStatus,
        ReconciliationDiscrepancyType type,
        ReconciliationAction recommendedAction,
        String actorId
    ) {
        LocalDateTime now = LocalDateTime.now();
        discrepancyRepository.upsertOpen(
            UUID.randomUUID(),
            runId,
            paymentId,
            type.name(),
            localStatus.name(),
            providerStatus.name(),
            recommendedAction.name(),
            now
        );
        ReconciliationDiscrepancy discrepancy = discrepancyRepository
            .findByPaymentIdAndStatus(
                paymentId,
                ReconciliationDiscrepancyStatus.OPEN
            )
            .orElseThrow();
        auditRepository.save(audit(
            runId,
            discrepancy.getId(),
            paymentId,
            "DISCREPANCY_OBSERVED",
            actorId,
            "type=" + type + ",localStatus=" + localStatus
                + ",providerStatus=" + providerStatus
                + ",recommendedAction=" + recommendedAction,
            now
        ));
        return discrepancy;
    }

    @Transactional
    public ReconciliationRun completeRun(
        UUID runId,
        int scannedCount,
        int discrepancyCount,
        String actorId
    ) {
        LocalDateTime now = LocalDateTime.now();
        ReconciliationRun run = runRepository.findById(runId).orElseThrow();
        run.complete(scannedCount, discrepancyCount, now);
        auditRepository.save(audit(
            runId, null, null, "RUN_COMPLETED", actorId,
            "scanned=" + scannedCount + ",discrepancies=" + discrepancyCount,
            now
        ));
        return run;
    }

    @Transactional
    public void failRun(
        UUID runId,
        int scannedCount,
        int discrepancyCount,
        String actorId,
        RuntimeException failure
    ) {
        LocalDateTime now = LocalDateTime.now();
        ReconciliationRun run = runRepository.findById(runId).orElseThrow();
        String reason = failure.getMessage() == null
            ? failure.getClass().getSimpleName()
            : failure.getMessage();
        run.fail(scannedCount, discrepancyCount, reason, now);
        auditRepository.save(audit(
            runId, null, null, "RUN_FAILED", actorId,
            "failure=" + reason.substring(0, Math.min(reason.length(), 1900)),
            now
        ));
    }

    @Transactional
    public void recordContradictoryWebhook(
        UUID paymentId,
        PaymentStatus localStatus,
        PaymentProviderLookupStatus providerStatus,
        UUID providerEventId
    ) {
        recordObservation(
            null,
            paymentId,
            localStatus,
            providerStatus,
            ReconciliationDiscrepancyType.CONTRADICTORY_PROVIDER_STATUS,
            ReconciliationAction.IGNORE,
            "SYSTEM_WEBHOOK"
        );
        auditRepository.save(audit(
            null, null, paymentId, "CONTRADICTORY_WEBHOOK_RECEIVED",
            "SYSTEM_WEBHOOK", "providerEventId=" + providerEventId,
            LocalDateTime.now()
        ));
    }

    @Transactional
    public void closeOpenDiscrepancyFromWebhook(
        UUID paymentId,
        PaymentProviderLookupStatus providerStatus,
        UUID providerEventId
    ) {
        ReconciliationDiscrepancy discrepancy = discrepancyRepository
            .findOpenByPaymentIdForUpdate(paymentId)
            .orElse(null);
        if (discrepancy == null) {
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        discrepancy.resolve(
            "SYSTEM_WEBHOOK",
            "webhook:" + providerEventId,
            ReconciliationAction.IGNORE,
            "Final provider webhook applied before analyst resolution",
            providerStatus,
            now
        );
        auditRepository.save(audit(
            discrepancy.getRun() == null ? null : discrepancy.getRun().getId(),
            discrepancy.getId(), paymentId, "DISCREPANCY_CLOSED_BY_WEBHOOK",
            "SYSTEM_WEBHOOK", "providerEventId=" + providerEventId, now
        ));
    }

    private ReconciliationAuditRecord audit(
        UUID runId,
        UUID discrepancyId,
        UUID paymentId,
        String action,
        String actorId,
        String details,
        LocalDateTime now
    ) {
        return new ReconciliationAuditRecord(
            UUID.randomUUID(), runId, discrepancyId, paymentId,
            action, actorId, details, now
        );
    }
}
