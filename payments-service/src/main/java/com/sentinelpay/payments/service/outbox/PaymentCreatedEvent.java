package com.sentinelpay.payments.service.outbox;

import java.util.UUID;
import java.math.BigDecimal;

public record PaymentCreatedEvent(
    UUID paymentId,
    UUID senderWalletId,
    UUID receiverWalletId,
    BigDecimal amount,
    String currency
) {}
