package com.sentinelpay.payments.service.reconciliation;

import java.time.LocalDateTime;
import java.util.UUID;

import org.springframework.stereotype.Service;

import com.sentinelpay.payments.domain.Payment;
import com.sentinelpay.payments.domain.PaymentReservation;
import com.sentinelpay.payments.domain.PaymentStatus;
import com.sentinelpay.payments.domain.ReconciliationAction;
import com.sentinelpay.payments.domain.ReconciliationAuditRecord;
import com.sentinelpay.payments.domain.ReconciliationDiscrepancy;
import com.sentinelpay.payments.exception.ReconciliationConflictException;
import com.sentinelpay.payments.exception.ReconciliationNotFoundException;
import com.sentinelpay.payments.provider.PaymentProvider;
import com.sentinelpay.payments.provider.PaymentProviderLookupResult;
import com.sentinelpay.payments.provider.PaymentProviderLookupStatus;
import com.sentinelpay.payments.repository.PaymentRepository;
import com.sentinelpay.payments.repository.PaymentReservationRepository;
import com.sentinelpay.payments.repository.ReconciliationAuditRepository;
import com.sentinelpay.payments.repository.ReconciliationDiscrepancyRepository;
import com.sentinelpay.payments.service.LedgerService;

import jakarta.transaction.Transactional;

@Service
public class ReconciliationResolutionService {
    private final ReconciliationDiscrepancyRepository discrepancyRepository;
    private final ReconciliationAuditRepository auditRepository;
    private final PaymentRepository paymentRepository;
    private final PaymentReservationRepository reservationRepository;
    private final PaymentProvider paymentProvider;
    private final LedgerService ledgerService;

    public ReconciliationResolutionService(
        ReconciliationDiscrepancyRepository discrepancyRepository,
        ReconciliationAuditRepository auditRepository,
        PaymentRepository paymentRepository,
        PaymentReservationRepository reservationRepository,
        PaymentProvider paymentProvider,
        LedgerService ledgerService
    ) {
        this.discrepancyRepository = discrepancyRepository;
        this.auditRepository = auditRepository;
        this.paymentRepository = paymentRepository;
        this.reservationRepository = reservationRepository;
        this.paymentProvider = paymentProvider;
        this.ledgerService = ledgerService;
    }

    @Transactional
    public ReconciliationDiscrepancy resolve(
        UUID discrepancyId,
        int expectedVersion,
        ReconciliationAction requestedAction,
        String reason,
        String idempotencyKey,
        String actorId
    ) {
        ReconciliationDiscrepancy replay = discrepancyRepository
            .findByResolvedByAndResolutionIdempotencyKey(actorId, idempotencyKey)
            .orElse(null);
        if (replay != null) {
            if (!replay.getId().equals(discrepancyId) ||
                replay.getResolutionAction() != requestedAction ||
                !replay.getResolutionReason().equals(reason)) {
                throw new ReconciliationConflictException(
                    "Idempotency-Key was already used for another resolution"
                );
            }
            return replay;
        }

        ReconciliationDiscrepancy discrepancy = discrepancyRepository
            .findByIdForUpdate(discrepancyId)
            .orElseThrow(ReconciliationNotFoundException::new);
        if (!discrepancy.isOpen()) {
            throw new ReconciliationConflictException(
                "Reconciliation discrepancy is already final"
            );
        }
        if (discrepancy.getVersion() != expectedVersion) {
            throw new ReconciliationConflictException(
                "Reconciliation discrepancy version is stale"
            );
        }

        Payment payment = paymentRepository
            .findByIdForUpdate(discrepancy.getPaymentId())
            .orElseThrow(ReconciliationNotFoundException::new);
        PaymentReservation reservation = reservationRepository
            .findByPaymentIdForUpdate(payment.getId())
            .orElseThrow(() -> new ReconciliationConflictException(
                "Payment reservation is missing"
            ));

        PaymentProviderLookupResult provider = paymentProvider.lookupPayment(
            payment.getId()
        );
        validateLookup(payment, provider);
        ReconciliationAction serverAction = deriveAction(payment, provider.status());
        if (serverAction != requestedAction) {
            throw new ReconciliationConflictException(
                "Requested action is not permitted by current provider and local state; "
                    + "permitted action is " + serverAction
            );
        }

        if (requestedAction != ReconciliationAction.IGNORE &&
            !reservation.isActive()) {
            throw new ReconciliationConflictException(
                "Payment reservation is already final"
            );
        }

        if (payment.getProviderPaymentId() == null &&
            provider.providerPaymentId() != null) {
            payment.assignProviderPaymentId(provider.providerPaymentId());
        }

        switch (requestedAction) {
            case CAPTURE -> {
                ledgerService.settlePayment(payment);
                payment.transitionTo(PaymentStatus.SETTLED);
            }
            case RELEASE -> {
                ledgerService.releasePayment(payment);
                payment.transitionTo(PaymentStatus.FAILED);
            }
            case IGNORE -> {
                // Closing an operational discrepancy never changes money.
            }
        }

        LocalDateTime now = LocalDateTime.now();
        discrepancy.resolve(
            actorId,
            idempotencyKey,
            requestedAction,
            reason,
            provider.status(),
            now
        );
        ReconciliationDiscrepancy resolved = discrepancyRepository.saveAndFlush(
            discrepancy
        );
        auditRepository.save(new ReconciliationAuditRecord(
            UUID.randomUUID(),
            discrepancy.getRun() == null ? null : discrepancy.getRun().getId(),
            discrepancy.getId(),
            payment.getId(),
            "RESOLUTION_" + requestedAction,
            actorId,
            "providerStatus=" + provider.status()
                + ",localStatusBefore=" + discrepancy.getLocalPaymentStatus()
                + ",expectedVersion=" + expectedVersion
                + ",reason=" + reason,
            now
        ));
        return resolved;
    }

    private ReconciliationAction deriveAction(
        Payment payment,
        PaymentProviderLookupStatus providerStatus
    ) {
        if (payment.getStatus() == PaymentStatus.PROCESSING) {
            return switch (providerStatus) {
                case SUCCEEDED -> ReconciliationAction.CAPTURE;
                case DECLINED -> ReconciliationAction.RELEASE;
                case PENDING, NOT_FOUND -> ReconciliationAction.IGNORE;
            };
        }
        if ((payment.getStatus() == PaymentStatus.SETTLED &&
                providerStatus == PaymentProviderLookupStatus.SUCCEEDED) ||
            (payment.getStatus() == PaymentStatus.FAILED &&
                providerStatus == PaymentProviderLookupStatus.DECLINED)) {
            return ReconciliationAction.IGNORE;
        }
        throw new ReconciliationConflictException(
            "Current payment and provider states require investigation"
        );
    }

    private void validateLookup(
        Payment payment,
        PaymentProviderLookupResult provider
    ) {
        if (provider == null || provider.status() == null) {
            throw new ReconciliationConflictException(
                "PSP returned an invalid status lookup"
            );
        }
        if (provider.status() != PaymentProviderLookupStatus.NOT_FOUND &&
            (provider.providerPaymentId() == null ||
                provider.providerPaymentId().isBlank())) {
            throw new ReconciliationConflictException(
                "PSP lookup omitted the provider payment ID"
            );
        }
        if (payment.getProviderPaymentId() != null &&
            provider.providerPaymentId() != null &&
            !payment.getProviderPaymentId().equals(provider.providerPaymentId())) {
            throw new ReconciliationConflictException(
                "PSP lookup returned a different provider payment ID"
            );
        }
    }
}
