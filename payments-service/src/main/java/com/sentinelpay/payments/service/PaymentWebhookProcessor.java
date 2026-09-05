package com.sentinelpay.payments.service;

import org.springframework.stereotype.Service;

import com.sentinelpay.payments.domain.Payment;
import com.sentinelpay.payments.domain.PaymentStatus;
import com.sentinelpay.payments.provider.PaymentProviderWebhook;
import com.sentinelpay.payments.repository.PaymentRepository;
import com.sentinelpay.payments.repository.ProcessedEventRepository;

import jakarta.transaction.Transactional;

@Service
public class PaymentWebhookProcessor {
    private final PaymentRepository paymentRepository;
    private final ProcessedEventRepository processedEventRepository;
    private final LedgerService ledgerService;

    public PaymentWebhookProcessor(
        PaymentRepository paymentRepository,
        ProcessedEventRepository processedEventRepository, LedgerService ledgerService
    ) {
        this.paymentRepository = paymentRepository;
        this.processedEventRepository = processedEventRepository;
        this.ledgerService = ledgerService;
    }

    @Transactional
    public void process(PaymentProviderWebhook webhook) {
        Payment payment =
            paymentRepository
                .findByProviderPaymentIdForUpdate(webhook.providerPaymentId())
                .orElseThrow();

        int claimed = processedEventRepository.claimEvent(webhook.eventId());
        if (claimed == 0) {
            return;
        }

        if (payment.getStatus() == PaymentStatus.SETTLED ||
            payment.getStatus() == PaymentStatus.FAILED) {
            return;
        }

        if (payment.getStatus() != PaymentStatus.PROCESSING) {
            throw new IllegalStateException(
                "Only a processing payment can receive a final provider result"
            );
        }

        switch (webhook.status()) {
            case SUCCEEDED -> {
                ledgerService.settlePayment(payment);
                payment.transitionTo(PaymentStatus.SETTLED);
            }

            case DECLINED -> {
                ledgerService.releasePayment(payment);
                payment.transitionTo(PaymentStatus.FAILED);
            }
        }
    }
}
