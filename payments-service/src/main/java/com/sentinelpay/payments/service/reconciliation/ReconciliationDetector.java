package com.sentinelpay.payments.service.reconciliation;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import com.sentinelpay.payments.domain.Payment;
import com.sentinelpay.payments.domain.ReconciliationAction;
import com.sentinelpay.payments.domain.ReconciliationDiscrepancyType;
import com.sentinelpay.payments.domain.ReconciliationRun;
import com.sentinelpay.payments.provider.PaymentProvider;
import com.sentinelpay.payments.provider.PaymentProviderLookupResult;
import com.sentinelpay.payments.provider.PaymentProviderLookupStatus;
import com.sentinelpay.payments.repository.PaymentRepository;

@Service
public class ReconciliationDetector {
    private static final String SYSTEM_ACTOR = "SYSTEM_RECONCILIATION";

    private final PaymentRepository paymentRepository;
    private final PaymentProvider paymentProvider;
    private final ReconciliationPersistenceService persistenceService;
    private final long staleAfterSeconds;
    private final int batchSize;

    public ReconciliationDetector(
        PaymentRepository paymentRepository,
        PaymentProvider paymentProvider,
        ReconciliationPersistenceService persistenceService,
        @Value("${sentinelpay.reconciliation.stale-after-seconds:300}")
        long staleAfterSeconds,
        @Value("${sentinelpay.reconciliation.batch-size:100}") int batchSize
    ) {
        if (staleAfterSeconds < 1 || batchSize < 1 || batchSize > 1000) {
            throw new IllegalArgumentException(
                "Reconciliation threshold and batch size are invalid"
            );
        }
        this.paymentRepository = paymentRepository;
        this.paymentProvider = paymentProvider;
        this.persistenceService = persistenceService;
        this.staleAfterSeconds = staleAfterSeconds;
        this.batchSize = batchSize;
    }

    @Scheduled(fixedDelayString = "${sentinelpay.reconciliation.poll-ms:60000}")
    public void runScheduled() {
        run(SYSTEM_ACTOR);
    }

    public ReconciliationRun run(String actorId) {
        LocalDateTime cutoff = LocalDateTime.now().minusSeconds(staleAfterSeconds);
        ReconciliationRun run = persistenceService.startRun(cutoff, actorId);
        int scanned = 0;
        int discrepancies = 0;
        LocalDateTime cursorUpdatedAt = null;
        UUID cursorId = null;

        try {
            while (true) {
                List<Payment> candidates = cursorUpdatedAt == null
                    ? paymentRepository.findFirstReconciliationCandidates(
                        cutoff, PageRequest.of(0, batchSize)
                    )
                    : paymentRepository.findReconciliationCandidatesAfter(
                        cutoff,
                        cursorUpdatedAt,
                        cursorId,
                        PageRequest.of(0, batchSize)
                    );
                if (candidates.isEmpty()) {
                    break;
                }

                for (Payment payment : candidates) {
                    scanned++;
                    PaymentProviderLookupResult provider = paymentProvider
                        .lookupPayment(payment.getId());
                    validateLookup(payment, provider);
                    ReconciliationObservation observation = classify(provider);
                    persistenceService.recordObservation(
                        run.getId(),
                        payment.getId(),
                        payment.getStatus(),
                        provider.status(),
                        observation.type(),
                        observation.recommendedAction(),
                        actorId
                    );
                    discrepancies++;
                    cursorUpdatedAt = payment.getUpdatedAt();
                    cursorId = payment.getId();
                }
                if (candidates.size() < batchSize) {
                    break;
                }
            }
            return persistenceService.completeRun(
                run.getId(), scanned, discrepancies, actorId
            );
        } catch (RuntimeException failure) {
            persistenceService.failRun(
                run.getId(), scanned, discrepancies, actorId, failure
            );
            throw failure;
        }
    }

    private void validateLookup(
        Payment payment,
        PaymentProviderLookupResult lookup
    ) {
        if (lookup == null || lookup.status() == null) {
            throw new IllegalStateException("PSP returned an invalid status lookup");
        }
        if (lookup.status() != PaymentProviderLookupStatus.NOT_FOUND &&
            (lookup.providerPaymentId() == null ||
                lookup.providerPaymentId().isBlank())) {
            throw new IllegalStateException(
                "PSP lookup omitted the provider payment ID"
            );
        }
        if (payment.getProviderPaymentId() != null &&
            lookup.providerPaymentId() != null &&
            !payment.getProviderPaymentId().equals(lookup.providerPaymentId())) {
            throw new IllegalStateException(
                "PSP lookup returned a different provider payment ID"
            );
        }
    }

    private ReconciliationObservation classify(
        PaymentProviderLookupResult lookup
    ) {
        return switch (lookup.status()) {
            case SUCCEEDED -> new ReconciliationObservation(
                ReconciliationDiscrepancyType.MISSING_SUCCESS,
                ReconciliationAction.CAPTURE
            );
            case DECLINED -> new ReconciliationObservation(
                ReconciliationDiscrepancyType.MISSING_DECLINE,
                ReconciliationAction.RELEASE
            );
            case PENDING -> new ReconciliationObservation(
                ReconciliationDiscrepancyType.PROVIDER_PENDING,
                ReconciliationAction.IGNORE
            );
            case NOT_FOUND -> new ReconciliationObservation(
                ReconciliationDiscrepancyType.PROVIDER_PAYMENT_NOT_FOUND,
                ReconciliationAction.IGNORE
            );
        };
    }
}
