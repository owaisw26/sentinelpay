package com.sentinelpay.payments.exception;

public class FraudRuleServiceUnavailableException extends RuntimeException {
    public FraudRuleServiceUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
