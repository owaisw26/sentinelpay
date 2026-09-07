package com.sentinelpay.payments.domain;

public enum ReconciliationDiscrepancyType {
    MISSING_SUCCESS,
    MISSING_DECLINE,
    PROVIDER_PENDING,
    PROVIDER_PAYMENT_NOT_FOUND,
    CONTRADICTORY_PROVIDER_STATUS
}
