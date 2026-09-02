package com.sentinelpay.payments.controller.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record UserRequest(
    @NotBlank
    String name, 
    
    @NotBlank
    @Pattern(
        regexp = "CUSTOMER|ANALYST",
        message = "role must be CUSTOMER or ANALYST"
    )
    String role) {
}
