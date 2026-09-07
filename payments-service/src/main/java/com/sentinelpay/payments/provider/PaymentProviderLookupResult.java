package com.sentinelpay.payments.provider;

public record PaymentProviderLookupResult(
    String providerPaymentId,
    PaymentProviderLookupStatus status
) {}
