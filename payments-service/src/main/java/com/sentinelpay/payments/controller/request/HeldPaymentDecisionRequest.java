package com.sentinelpay.payments.controller.request;

import com.sentinelpay.payments.domain.HeldPaymentAction;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record HeldPaymentDecisionRequest(
    @Min(0) int expectedVersion,
    @NotNull HeldPaymentAction action,
    @NotBlank @Size(min = 3, max = 500) String reason
) {}
