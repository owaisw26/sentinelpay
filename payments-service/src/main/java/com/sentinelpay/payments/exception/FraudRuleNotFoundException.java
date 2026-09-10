package com.sentinelpay.payments.exception;

public class FraudRuleNotFoundException extends RuntimeException {
    public FraudRuleNotFoundException(String message) {
        super(message);
    }
}
