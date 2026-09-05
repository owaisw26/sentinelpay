package com.sentinelpay.payments.provider;

import java.util.UUID;

public record PaymentProviderWebhook(
    UUID eventId,
    String providerPaymentId,
    PaymentProviderWebhookStatus status
) {}
