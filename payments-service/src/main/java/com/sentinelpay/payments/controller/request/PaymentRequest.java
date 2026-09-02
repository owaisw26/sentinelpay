package com.sentinelpay.payments.controller.request;

import java.math.BigDecimal;
import java.util.UUID;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;

public record PaymentRequest(
    @NotNull
    UUID senderWallet,

    @NotNull
    UUID receiverWallet,

    @NotBlank
    String reference,

    @NotNull
    @Positive
    BigDecimal amount,

    @NotBlank
    @Pattern(
        regexp = "[A-Z]{3}",
        message = "currency must be a three-letter uppercase code"
    )
    String currency
) {
}
