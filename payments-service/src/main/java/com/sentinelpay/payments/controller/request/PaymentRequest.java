package com.sentinelpay.payments.controller.request;

import java.math.BigDecimal;
import java.util.UUID;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Size;

public record PaymentRequest(
    @NotNull
    UUID senderWalletId,

    @NotNull
    UUID receiverWalletId,

    @NotBlank
    @Size(max = 140)
    String reference,

    @NotNull
    @Positive
    @Digits(integer = 17, fraction = 2)
    BigDecimal amount,

    @NotBlank
    @Pattern(
        regexp = "AUD",
        message = "only AUD payments are supported"
    )
    String currency,

    @NotNull
    UUID payeeCheckId,

    boolean acceptNameMismatch
) {
}
