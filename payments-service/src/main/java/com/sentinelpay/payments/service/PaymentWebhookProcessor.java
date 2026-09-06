package com.sentinelpay.payments.service;

import java.time.LocalDateTime;

import org.springframework.stereotype.Service;

import com.sentinelpay.payments.domain.Payment;
import com.sentinelpay.payments.domain.PaymentStatus;
import com.sentinelpay.payments.domain.WebhookReceipt;
import com.sentinelpay.payments.exception.ConflictingWebhookEventException;
import com.sentinelpay.payments.exception.InvalidWebhookPayloadException;
import com.sentinelpay.payments.provider.PaymentProviderWebhook;
import com.sentinelpay.payments.repository.PaymentRepository;
import com.sentinelpay.payments.repository.WebhookReceiptRepository;
import com.sentinelpay.payments.security.PayloadHasher;

import jakarta.transaction.Transactional;

@Service
public class PaymentWebhookProcessor {
    private final PaymentRepository paymentRepository;
    private final WebhookReceiptRepository webhookReceiptRepository;
    private final LedgerService ledgerService;

    public PaymentWebhookProcessor(
        PaymentRepository paymentRepository,
        WebhookReceiptRepository webhookReceiptRepository,
        LedgerService ledgerService
    ) {
        this.paymentRepository = paymentRepository;
        this.webhookReceiptRepository = webhookReceiptRepository;
        this.ledgerService = ledgerService;
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
                !existing.getEventStatus().equals(webhook.status().name())) {
                throw new ConflictingWebhookEventException();
            }
            return;
        }

        Payment payment =
            paymentRepository
                .findByProviderPaymentIdForUpdate(webhook.providerPaymentId())
                .orElseThrow();

        if (payment.getStatus() == PaymentStatus.SETTLED ||
            payment.getStatus() == PaymentStatus.FAILED) {
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
