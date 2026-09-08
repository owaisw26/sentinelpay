package com.sentinelpay.payments.exception;

public class PayeeVerificationUnavailableException extends RuntimeException {
    public PayeeVerificationUnavailableException() {
        super("Payee verification is temporarily unavailable");
    }
}
