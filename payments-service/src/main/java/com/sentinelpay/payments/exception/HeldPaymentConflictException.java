package com.sentinelpay.payments.exception;

public class HeldPaymentConflictException extends RuntimeException {
    public HeldPaymentConflictException(String message) {
        super(message);
    }
}
