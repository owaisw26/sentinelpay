package com.sentinelpay.payments.exception;

public class FraudRuleConflictException extends RuntimeException {
    public FraudRuleConflictException(String message) {
        super(message);
    }
}
