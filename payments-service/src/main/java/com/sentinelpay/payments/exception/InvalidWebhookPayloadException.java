package com.sentinelpay.payments.exception;

public class InvalidWebhookPayloadException extends RuntimeException {
    public InvalidWebhookPayloadException() {
        super("Webhook payload is invalid");
    }
}
