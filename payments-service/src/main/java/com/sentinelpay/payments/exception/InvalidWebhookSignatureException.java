package com.sentinelpay.payments.exception;

public class InvalidWebhookSignatureException extends RuntimeException {
    public InvalidWebhookSignatureException() {
        super("Webhook signature is missing, invalid, or expired");
    }
}
