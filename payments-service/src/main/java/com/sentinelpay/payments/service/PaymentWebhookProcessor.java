package com.sentinelpay.payments.service;

import java.time.LocalDateTime;

import org.springframework.stereotype.Service;

import com.sentinelpay.payments.domain.Payment;
import com.sentinelpay.payments.domain.PaymentStatus;
import com.sentinelpay.payments.domain.WebhookReceipt;
import com.sentinelpay.payments.exception.ConflictingWebhookEventException;
import com.sentinelpay.payments.exception.InvalidWebhookPayloadException;
import com.sentinelpay.payments.provider.PaymentProviderLookupStatus;
import com.sentinelpay.payments.provider.PaymentProviderWebhook;
import com.sentinelpay.payments.provider.PaymentProviderWebhookStatus;
import com.sentinelpay.payments.repository.PaymentRepository;
import com.sentinelpay.payments.repository.WebhookReceiptRepository;
import com.sentinelpay.payments.security.PayloadHasher;
import com.sentinelpay.payments.service.reconciliation.ReconciliationPersistenceService;

import jakarta.transaction.Transactional;

@Service
public class PaymentWebhookProcessor {
    private final PaymentRepository paymentRepository;
    private final WebhookReceiptRepository webhookReceiptRepository;
    private final LedgerService ledgerService;
    private final ReconciliationPersistenceService reconciliationService;

    public PaymentWebhookProcessor(
        PaymentRepository paymentRepository,
        WebhookReceiptRepository webhookReceiptRepository,
        LedgerService ledgerService,
        ReconciliationPersistenceService reconciliationService
    ) {
        this.paymentRepository = paymentRepository;
        this.webhookReceiptRepository = webhookReceiptRepository;
        this.ledgerService = ledgerService;
        this.reconciliationService = reconciliationService;
    }

    @Transactional
    public void process(PaymentProviderWebhook webhook) {
        String canonicalPayload = webhook == null
            ? "null"
            : webhook.eventId() + "\u001f" + webhook.providerPaymentId() +
                "\u001f" + webhook.status();
        process(webhook, PayloadHasher.sha256(canonicalPayload));
    }

    @Transactional
    public void process(PaymentProviderWebhook webhook, String payloadHash) {
        validate(webhook, payloadHash);
        LocalDateTime now = LocalDateTime.now();
        int claimed = webhookReceiptRepository.claim(
            webhook.eventId(),
            webhook.providerPaymentId(),
            webhook.status().name(),
            payloadHash,
            now
        );
        if (claimed == 0) {
            WebhookReceipt existing = webhookReceiptRepository
                .findById(webhook.eventId())
                .orElseThrow();
            if (!existing.getProviderPaymentId().equals(
                    webhook.providerPaymentId()) ||
                !existing.getEventStatus().equals(webhook.status().name()) ||
                !existing.getPayloadSha256().equals(payloadHash)) {
                throw new ConflictingWebhookEventException();
            }
            webhookReceiptRepository.recordDuplicateDelivery(
                webhook.eventId(), now
            );
            return;
        }

        Payment payment =
            paymentRepository
                .findByProviderPaymentIdForUpdate(webhook.providerPaymentId())
                .orElseThrow();

        if (payment.getStatus() == PaymentStatus.SETTLED ||
            payment.getStatus() == PaymentStatus.FAILED) {
            if (isContradictory(payment.getStatus(), webhook.status())) {
                reconciliationService.recordContradictoryWebhook(
                    payment.getId(),
                    payment.getStatus(),
                    toLookupStatus(webhook.status()),
                    webhook.eventId()
                );
            }
            webhookReceiptRepository.markProcessed(webhook.eventId(), now);
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
        webhookReceiptRepository.markProcessed(webhook.eventId(), now);
        reconciliationService.closeOpenDiscrepancyFromWebhook(
            payment.getId(),
            toLookupStatus(webhook.status()),
            webhook.eventId()
        );
    }

    private boolean isContradictory(
        PaymentStatus localStatus,
        PaymentProviderWebhookStatus providerStatus
    ) {
        return (localStatus == PaymentStatus.SETTLED &&
                providerStatus == PaymentProviderWebhookStatus.DECLINED) ||
            (localStatus == PaymentStatus.FAILED &&
                providerStatus == PaymentProviderWebhookStatus.SUCCEEDED);
    }

    private PaymentProviderLookupStatus toLookupStatus(
        PaymentProviderWebhookStatus status
    ) {
        return status == PaymentProviderWebhookStatus.SUCCEEDED
            ? PaymentProviderLookupStatus.SUCCEEDED
            : PaymentProviderLookupStatus.DECLINED;
    }

    private void validate(PaymentProviderWebhook webhook, String payloadHash) {
        if (webhook == null || webhook.eventId() == null ||
            webhook.providerPaymentId() == null ||
            webhook.providerPaymentId().isBlank() ||
            webhook.providerPaymentId().length() > 255 ||
            webhook.status() == null || payloadHash == null ||
            payloadHash.length() != 64) {
            throw new InvalidWebhookPayloadException();
        }
    }
}
