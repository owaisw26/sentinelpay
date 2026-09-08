package com.sentinelpay.payments.controller.request;

import java.util.UUID;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record PayeeCheckRequest(
    @NotNull UUID receiverWalletId,
    @NotBlank @Size(max = 200) String suppliedName
) {}
