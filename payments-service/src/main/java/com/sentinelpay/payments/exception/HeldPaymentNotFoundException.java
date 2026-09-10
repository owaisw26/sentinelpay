package com.sentinelpay.payments.exception;

public class HeldPaymentNotFoundException extends RuntimeException {
    public HeldPaymentNotFoundException() {
        super("Held payment not found");
    }
}
