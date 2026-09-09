package com.sentinelpay.payments.service.outbox;

import java.util.Optional;

import org.springframework.stereotype.Service;

import com.sentinelpay.payments.provider.PaymentProvider;
import com.sentinelpay.payments.provider.PaymentProviderResponse;

@Service
public class PaymentEventProcessor {
    private final PaymentProcessingService paymentProcessingService;
    private final PaymentProvider paymentProvider;

    public PaymentEventProcessor(
        PaymentProcessingService paymentProcessingService,
        PaymentProvider paymentProvider
    ) {
        this.paymentProcessingService = paymentProcessingService;
        this.paymentProvider = paymentProvider;
    }

    public void process(OutboxMessage message) {
        if (!("PAYMENT_CREATED".equals(message.eventType()) ||
              "PAYMENT_APPROVED".equals(message.eventType())) ||
            paymentProcessingService.isProcessed(message.eventId())) {
            return;
        }

        Optional<PaymentProcessingClaim> possibleClaim =
            paymentProcessingService.claimProcessing(
                message.eventId(),
                message.aggregateId()
            );

        if (possibleClaim.isEmpty()) {
            return;
        }

        PaymentProcessingClaim claim = possibleClaim.get();

        try {
            PaymentProviderResponse response = paymentProvider.processPayment(
                claim.payment(),
                claim.providerIdempotencyKey()
            );

            paymentProcessingService.completeProcessing(
                message.eventId(),
                claim.payment().getId(),
                claim.leaseToken(),
                response
            );
            paymentProvider.afterProcessingCompleted(claim.payment(), response);
        } catch (RuntimeException failure) {
            try {
                paymentProcessingService.recordAttemptFailure(
                    claim.payment().getId(),
                    claim.leaseToken(),
                    failure
                );
            } catch (RuntimeException recordingFailure) {
                failure.addSuppressed(recordingFailure);
            }
            throw failure;
        }
    }
}
