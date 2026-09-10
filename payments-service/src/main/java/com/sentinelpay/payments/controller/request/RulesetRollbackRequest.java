package com.sentinelpay.payments.controller.request;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record RulesetRollbackRequest(
    @Min(0) long expectedRulesetVersion,
    @NotBlank @Size(min = 3, max = 500) String reason
) {}
