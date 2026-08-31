package com.sentinelpay.payments.exception;

public class InvalidPaymentTransition extends RuntimeException {
    public InvalidPaymentTransition(String stateOne, String stateTwo) {
        super("%s to %s is an invalid transition".formatted(stateOne, stateTwo));
    }
}
