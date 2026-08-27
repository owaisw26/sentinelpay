package com.sentinelpay.payments.controller.RequestSchema;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record WalletRequest(
    @NotBlank
    @Pattern(regexp = "[A-Z]{3}")
    String currency
) {
    
}
