package com.sentinelpay.payments.exception;

public class ConflictingWebhookEventException extends RuntimeException {
    public ConflictingWebhookEventException() {
        super("Provider event ID was reused with different content");
    }
}
