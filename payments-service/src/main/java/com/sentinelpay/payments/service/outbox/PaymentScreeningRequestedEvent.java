package com.sentinelpay.payments.service.outbox;

import java.math.BigDecimal;
import java.util.UUID;

import com.sentinelpay.payments.domain.PayeeCheckOutcome;

public record PaymentScreeningRequestedEvent(
    UUID paymentId,
    UUID customerId,
    UUID payeeId,
    BigDecimal amount,
    String currency,
    String deviceToken,
    PayeeCheckOutcome nameCheckOutcome,
    boolean acceptedNameMismatch
) {}
