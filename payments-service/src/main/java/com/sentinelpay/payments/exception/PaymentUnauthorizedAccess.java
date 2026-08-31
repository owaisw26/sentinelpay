package com.sentinelpay.payments.exception;

import java.util.UUID;

public class PaymentUnauthorizedAccess extends RuntimeException {
    public PaymentUnauthorizedAccess(UUID userId, UUID paymentId) {
        super("User " + userId + " does not have access to payment " + paymentId);
    }
}
