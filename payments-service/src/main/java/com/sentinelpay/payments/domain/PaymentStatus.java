package com.sentinelpay.payments.domain;

public enum PaymentStatus {
    CREATED,
    SCREENING,
    APPROVED,
    PROCESSING,
    SETTLED,
    FAILED,
    BLOCKED
}
