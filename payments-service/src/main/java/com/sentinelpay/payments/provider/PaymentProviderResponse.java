package com.sentinelpay.payments.provider;

public record PaymentProviderResponse(
    String providerPaymentId,
    PaymentProviderStatus status
) {};