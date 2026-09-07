package com.sentinelpay.payments.provider;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.sentinelpay.payments.domain.Payment;
import com.sentinelpay.payments.service.PaymentWebhookProcessor;

@Component
public class FakePaymentProvider implements PaymentProvider{
    private final FakePspMode mode;
    private final PaymentWebhookProcessor webhookProcessor;

    private final Map<UUID, PaymentProviderResponse> processedPayments = new ConcurrentHashMap<>();
    private final Map<UUID, PaymentProviderLookupStatus> providerStatuses =
        new ConcurrentHashMap<>();
    private final Map<UUID, UUID> webhookEventIds = new ConcurrentHashMap<>();

    @Autowired
    public FakePaymentProvider(
        @Value ("${sentinelpay.psp.mode:SUCCESS}") String mode,
        PaymentWebhookProcessor webhookProcessor
    ) {
        this.mode = FakePspMode.valueOf(mode.toUpperCase());
        this.webhookProcessor = webhookProcessor;
    }

    public FakePaymentProvider(String mode) {
        this.mode = FakePspMode.valueOf(mode.toUpperCase());
        this.webhookProcessor = null;
    }

    @Override
    public PaymentProviderResponse processPayment(
        Payment payment,
        UUID providerIdempotencyKey
    ) {
        if (!payment.getId().equals(providerIdempotencyKey)) {
            throw new IllegalArgumentException(
                "Provider idempotency key must be the internal payment ID"
            );
        }

        PaymentProviderResponse accepted = accepted(providerIdempotencyKey);
        switch (mode) {
            case SUCCESS -> providerStatuses.putIfAbsent(
                providerIdempotencyKey,
                PaymentProviderLookupStatus.PENDING
            );

            case MISSING_WEBHOOK_SUCCESS, DUPLICATE_WEBHOOK ->
                providerStatuses.put(
                    providerIdempotencyKey,
                    PaymentProviderLookupStatus.SUCCEEDED
                );

            // The PSP accepted the request for asynchronous processing. Its
            // final decline is delivered through the webhook callback below.
            case DECLINE, MISSING_WEBHOOK_DECLINE -> providerStatuses.put(
                providerIdempotencyKey,
                PaymentProviderLookupStatus.DECLINED
            );

            case TIMEOUT -> {
                providerStatuses.putIfAbsent(
                    providerIdempotencyKey,
                    PaymentProviderLookupStatus.PENDING
                );
                throw new PaymentProviderTimeoutException(
                    "Fake PSP timed out"
                );

            }
            case CONTRADICTORY_STATUS -> providerStatuses.put(
                providerIdempotencyKey,
                PaymentProviderLookupStatus.SUCCEEDED
            );
        }
        return accepted;
    }

    @Override
    public PaymentProviderLookupResult lookupPayment(UUID providerIdempotencyKey) {
        PaymentProviderResponse response = processedPayments.get(
            providerIdempotencyKey
        );
        if (response == null) {
            return new PaymentProviderLookupResult(
                null,
                PaymentProviderLookupStatus.NOT_FOUND
            );
        }
        return new PaymentProviderLookupResult(
            response.providerPaymentId(),
            providerStatuses.getOrDefault(
                providerIdempotencyKey,
                PaymentProviderLookupStatus.PENDING
            )
        );
    }

    @Override
    public void afterProcessingCompleted(
        Payment payment,
        PaymentProviderResponse response
    ) {
        if (webhookProcessor == null) {
            return;
        }

        switch (mode) {
            case DECLINE -> deliverWebhook(
                payment,
                response,
                PaymentProviderWebhookStatus.DECLINED
            );
            case DUPLICATE_WEBHOOK -> {
                PaymentProviderWebhook webhook = createWebhook(
                    payment,
                    response,
                    PaymentProviderWebhookStatus.SUCCEEDED
                );
                webhookProcessor.process(webhook);
                webhookProcessor.process(webhook);
            }
            case CONTRADICTORY_STATUS -> {
                webhookProcessor.process(createWebhook(
                    payment,
                    response,
                    PaymentProviderWebhookStatus.SUCCEEDED
                ));
                providerStatuses.put(
                    payment.getId(),
                    PaymentProviderLookupStatus.DECLINED
                );
                webhookProcessor.process(new PaymentProviderWebhook(
                    UUID.randomUUID(),
                    response.providerPaymentId(),
                    PaymentProviderWebhookStatus.DECLINED
                ));
            }
            case SUCCESS, MISSING_WEBHOOK_SUCCESS,
                MISSING_WEBHOOK_DECLINE, TIMEOUT -> {
                // SUCCESS remains accepted until a webhook is supplied by the
                // caller. Missing-webhook modes intentionally emit nothing,
                // and TIMEOUT never reaches this callback.
            }
        }
    }

    private PaymentProviderResponse accepted(UUID providerIdempotencyKey) {
        return processedPayments.computeIfAbsent(
            providerIdempotencyKey,
            ignored -> new PaymentProviderResponse(
                UUID.randomUUID().toString(),
                PaymentProviderStatus.ACCEPTED
            )
        );
    }

    private void deliverWebhook(
        Payment payment,
        PaymentProviderResponse response,
        PaymentProviderWebhookStatus status
    ) {
        webhookProcessor.process(createWebhook(payment, response, status));
    }

    private PaymentProviderWebhook createWebhook(
        Payment payment,
        PaymentProviderResponse response,
        PaymentProviderWebhookStatus status
    ) {
        UUID eventId = webhookEventIds.computeIfAbsent(
            payment.getId(),
            ignored -> UUID.randomUUID()
        );

        return new PaymentProviderWebhook(
            eventId,
            response.providerPaymentId(),
            status
        );
    }
}
