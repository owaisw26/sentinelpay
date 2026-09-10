package com.sentinelpay.payments.controller.response;

import java.util.UUID;

import com.sentinelpay.payments.domain.User;

public record DevPersonaResponse(
    UUID userId,
    String name,
    String role
) {
    public static DevPersonaResponse from(User user) {
        return new DevPersonaResponse(
            user.getUserId(), user.getName(), user.getRole()
        );
    }
}
