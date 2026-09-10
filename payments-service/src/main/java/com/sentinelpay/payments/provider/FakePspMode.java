package com.sentinelpay.payments.provider;

public enum FakePspMode {
    SUCCESS,
    AUTO_SUCCESS,
    MISSING_WEBHOOK_SUCCESS,
    MISSING_WEBHOOK_DECLINE,
    DECLINE,
    TIMEOUT,
    DUPLICATE_WEBHOOK,
    CONTRADICTORY_STATUS
}
