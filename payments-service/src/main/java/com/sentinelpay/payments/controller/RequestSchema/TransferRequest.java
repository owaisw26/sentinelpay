package com.sentinelpay.payments.controller.RequestSchema;

import java.math.BigDecimal;
import java.util.UUID;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record TransferRequest(
    @NotNull
    UUID senderWallet,

    @NotNull
    UUID receiverWallet,

    @NotNull
    String reference,

    @NotNull
    @Positive
    BigDecimal amount
) {
    
}
