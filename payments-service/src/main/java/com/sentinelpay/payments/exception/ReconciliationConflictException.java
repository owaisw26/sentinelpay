package com.sentinelpay.payments.exception;

public class ReconciliationConflictException extends RuntimeException {
    public ReconciliationConflictException(String message) {
        super(message);
    }
}
