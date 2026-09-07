package com.sentinelpay.payments.controller.request;

import com.sentinelpay.payments.domain.ReconciliationAction;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record ReconciliationResolutionRequest(
    @NotNull @Min(0) Integer expectedVersion,
    @NotNull ReconciliationAction action,
    @NotBlank @Size(max = 500) String reason
) {}
