package com.sentinelpay.payments.controller.response;

import java.time.LocalDateTime;
import java.util.UUID;

import com.sentinelpay.payments.domain.User;

public record UserResponse(
    UUID userId,
    String name,
    LocalDateTime createdAt
) {
    public static UserResponse from(User user) {
        return new UserResponse(
            user.getUserId(),
            user.getName(),
            user.getCreatedAt()
        );
    }
}
