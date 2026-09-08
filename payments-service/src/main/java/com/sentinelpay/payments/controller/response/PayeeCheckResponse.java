package com.sentinelpay.payments.controller.response;

import java.time.LocalDateTime;
import java.util.UUID;

import com.sentinelpay.payments.domain.PayeeCheck;
import com.sentinelpay.payments.domain.PayeeCheckOutcome;
import com.sentinelpay.payments.domain.PayeeCheckReason;

public record PayeeCheckResponse(
    UUID checkId,
    PayeeCheckOutcome outcome,
    PayeeCheckReason reasonCode,
    LocalDateTime expiresAt
) {
    public static PayeeCheckResponse from(PayeeCheck check) {
        return new PayeeCheckResponse(
            check.getId(), check.getOutcome(), check.getReasonCode(),
            check.getExpiresAt()
        );
    }
}
