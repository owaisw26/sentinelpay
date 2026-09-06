package com.sentinelpay.payments.controller.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record UserRequest(
    @NotBlank
    @Size(max = 100)
    String name) {
}
