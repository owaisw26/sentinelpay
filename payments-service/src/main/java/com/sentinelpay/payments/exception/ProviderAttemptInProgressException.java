package com.sentinelpay.payments.exception;

public class ProviderAttemptInProgressException extends RuntimeException {
    public ProviderAttemptInProgressException() {
        super("Another worker currently owns the provider attempt");
    }
}
