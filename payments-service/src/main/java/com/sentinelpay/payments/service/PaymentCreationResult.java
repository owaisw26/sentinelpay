package com.sentinelpay.payments.service;

import com.sentinelpay.payments.controller.response.PaymentResponse;
import com.sentinelpay.payments.domain.Payment;

public record PaymentCreationResult(
    Payment payment,
    PaymentResponse response,
    boolean replayed
) {}
