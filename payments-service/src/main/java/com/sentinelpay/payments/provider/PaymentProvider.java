package com.sentinelpay.payments.provider;

import java.util.UUID;

import com.sentinelpay.payments.domain.Payment;

public interface PaymentProvider {
    PaymentProviderResponse processPayment(
        Payment payment,
        UUID providerIdempotencyKey
    );

    default void afterProcessingCompleted(
        Payment payment,
        PaymentProviderResponse response
    ) {
        // Real PSPs deliver their final result independently. Test providers can
        // use this callback to simulate that delivery after the provider ID has
        // been committed locally and is available to webhook lookup.
    }
}
