package com.sentinelpay.payments.service.outbox;

import java.util.UUID;

import com.sentinelpay.payments.domain.Payment;

public record PaymentProcessingClaim(
    Payment payment,
    UUID providerIdempotencyKey,
    UUID leaseToken
) {}
