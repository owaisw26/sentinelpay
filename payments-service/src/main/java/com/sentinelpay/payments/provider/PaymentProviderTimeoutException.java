package com.sentinelpay.payments.provider;

public class PaymentProviderTimeoutException extends RuntimeException {
    public PaymentProviderTimeoutException(String message) {
        super(message);
    }
}
