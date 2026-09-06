package com.sentinelpay.payments.domain;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class PaymentProviderIdTest {

    @Test
    void assignsProviderPaymentIdIdempotently() {
        Payment payment = payment();

        payment.assignProviderPaymentId("provider-id");

        assertEquals("provider-id", payment.getProviderPaymentId());
        assertDoesNotThrow(
            () -> payment.assignProviderPaymentId("provider-id")
        );
    }

    @Test
    void rejectsAReplacementProviderPaymentId() {
        Payment payment = payment();
        payment.assignProviderPaymentId("provider-id");

        assertThrows(
            IllegalStateException.class,
            () -> payment.assignProviderPaymentId("different-provider-id")
        );
    }

    private Payment payment() {
        LocalDateTime now = LocalDateTime.now();

        return new Payment(
            UUID.randomUUID(),
            1,
            null,
            null,
            BigDecimal.TEN,
            "AUD",
            "provider-id-test",
            PaymentStatus.PROCESSING,
            UUID.randomUUID().toString(),
            "request-hash",
            now,
            now
        );
    }
}
