package com.sentinelpay.payments.exception;

public class InvalidReconciliationRequestException extends RuntimeException {
    public InvalidReconciliationRequestException(String message) {
        super(message);
    }
}
