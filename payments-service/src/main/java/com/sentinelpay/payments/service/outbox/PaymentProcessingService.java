package com.sentinelpay.payments.service.outbox;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.sentinelpay.payments.domain.Payment;
import com.sentinelpay.payments.domain.PaymentStatus;
import com.sentinelpay.payments.domain.ProviderAttempt;
import com.sentinelpay.payments.exception.ProviderAttemptInProgressException;
import com.sentinelpay.payments.provider.PaymentProviderResponse;
import com.sentinelpay.payments.provider.PaymentProviderStatus;
import com.sentinelpay.payments.repository.PaymentRepository;
import com.sentinelpay.payments.repository.ProcessedEventRepository;
import com.sentinelpay.payments.repository.ProviderAttemptRepository;
import com.sentinelpay.payments.service.LedgerService;

import jakarta.transaction.Transactional;

@Service
public class PaymentProcessingService {
    private final PaymentRepository paymentRepository;
    private final ProcessedEventRepository processedEventRepository;
    private final ProviderAttemptRepository providerAttemptRepository;
    private final LedgerService ledgerService;
    private final long leaseSeconds;

    public PaymentProcessingService(
        PaymentRepository paymentRepository,
        ProcessedEventRepository processedEventRepository,
        ProviderAttemptRepository providerAttemptRepository,
        LedgerService ledgerService,
        @Value("${sentinelpay.psp.attempt-lease-seconds:30}") long leaseSeconds
    ) {
        this.paymentRepository = paymentRepository;
        this.processedEventRepository = processedEventRepository;
        this.providerAttemptRepository = providerAttemptRepository;
        this.ledgerService = ledgerService;
        this.leaseSeconds = leaseSeconds;
    }

    public boolean isProcessed(UUID eventId) {
        return processedEventRepository.existsById(eventId);
    }

    @Transactional
    public Optional<PaymentProcessingClaim> claimProcessing(
        UUID eventId,
        UUID paymentId
    ) {
        if (processedEventRepository.existsById(eventId)) {
            return Optional.empty();
        }

        Payment payment = paymentRepository.findByIdForUpdate(paymentId)
            .orElseThrow();

        if (payment.getStatus() == PaymentStatus.SETTLED ||
            payment.getStatus() == PaymentStatus.FAILED ||
            payment.getStatus() == PaymentStatus.BLOCKED) {
            processedEventRepository.claimEvent(eventId);
            return Optional.empty();
        }

        if (payment.getScreeningSequence() == null) {
            // Compatibility path used only when risk screening is explicitly
            // disabled (for legacy tests/local migration work).
            advanceToApproved(payment);
            ledgerService.reservePayment(payment);
            if (payment.getStatus() == PaymentStatus.APPROVED) {
                payment.transitionTo(PaymentStatus.PROCESSING);
            }
        }

        if (payment.getStatus() != PaymentStatus.PROCESSING) {
            throw new IllegalStateException(
                "Payment has not completed risk approval"
            );
        }

        LocalDateTime now = LocalDateTime.now();
        UUID leaseToken = UUID.randomUUID();
        ProviderAttempt attempt = providerAttemptRepository
            .findByPaymentIdForUpdate(paymentId)
            .orElse(null);

        if (attempt == null) {
            attempt = new ProviderAttempt(
                paymentId,
                eventId,
                leaseToken,
                now.plusSeconds(leaseSeconds),
                now
            );
            providerAttemptRepository.save(attempt);
        } else {
            if (attempt.isCompleted()) {
                processedEventRepository.claimEvent(eventId);
                return Optional.empty();
            }
            if (attempt.hasActiveLease(now)) {
                throw new ProviderAttemptInProgressException();
            }
            attempt.claim(leaseToken, now.plusSeconds(leaseSeconds), now);
        }

        return Optional.of(new PaymentProcessingClaim(
            payment,
            attempt.getIdempotencyKey(),
            leaseToken
        ));
    }

    @Transactional
    public void completeProcessing(
        UUID eventId,
        UUID paymentId,
        UUID leaseToken,
        PaymentProviderResponse response
    ) {
        Payment payment = paymentRepository.findByIdForUpdate(paymentId)
            .orElseThrow();
        ProviderAttempt attempt = providerAttemptRepository
            .findByPaymentIdForUpdate(paymentId)
            .orElseThrow();

        if (attempt.isCompleted()) {
            return;
        }
        if (!attempt.ownsLease(leaseToken)) {
            throw new ProviderAttemptInProgressException();
        }

        payment.assignProviderPaymentId(response.providerPaymentId());

        if (response.status() == PaymentProviderStatus.DECLINED) {
            ledgerService.releasePayment(payment);
            payment.transitionTo(PaymentStatus.FAILED);
        }

        attempt.complete(
            leaseToken,
            response.providerPaymentId(),
            LocalDateTime.now()
        );
        processedEventRepository.claimEvent(eventId);
    }

    @Transactional
    public void recordAttemptFailure(
        UUID paymentId,
        UUID leaseToken,
        RuntimeException failure
    ) {
        paymentRepository.findByIdForUpdate(paymentId).orElseThrow();
        ProviderAttempt attempt = providerAttemptRepository
            .findByPaymentIdForUpdate(paymentId)
            .orElseThrow();

        if (!attempt.isCompleted() && attempt.ownsLease(leaseToken)) {
            String message = failure.getMessage();
            attempt.releaseForRetry(
                leaseToken,
                message == null ? failure.getClass().getSimpleName() : message,
                LocalDateTime.now()
            );
        }
    }

    private void advanceToApproved(Payment payment) {
        if (payment.getStatus() == PaymentStatus.CREATED) {
            payment.transitionTo(PaymentStatus.SCREENING);
        }
        if (payment.getStatus() == PaymentStatus.SCREENING) {
            payment.transitionTo(PaymentStatus.APPROVED);
        }
    }
}
