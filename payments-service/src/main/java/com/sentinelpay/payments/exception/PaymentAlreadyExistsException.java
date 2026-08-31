package com.sentinelpay.payments.exception;

public class PaymentAlreadyExistsException extends RuntimeException {
    public PaymentAlreadyExistsException() {
        super("The payment already exists");
    }
}
