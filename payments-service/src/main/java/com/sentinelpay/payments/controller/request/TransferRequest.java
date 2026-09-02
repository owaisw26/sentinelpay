package com.sentinelpay.payments.controller.request;

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
