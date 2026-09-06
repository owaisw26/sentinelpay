package com.sentinelpay.payments.controller.response;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

import com.sentinelpay.payments.domain.Payment;
import com.sentinelpay.payments.domain.PaymentStatus;

public record PaymentResponse(
    UUID id,
    UUID senderWalletId,
    UUID receiverWalletId,
    BigDecimal amount,
    String currency,
    String reference,
    PaymentStatus status,
    LocalDateTime createdAt,
    LocalDateTime updatedAt
) {
    public static PaymentResponse from(Payment payment) {
        return new PaymentResponse(
            payment.getId(),
            payment.getSenderWallet().getId(),
            payment.getReceiverWallet().getId(),
            payment.getAmount(),
            payment.getCurrency(),
            payment.getReference(),
            payment.getStatus(),
            payment.getCreatedAt(),
            payment.getUpdatedAt()
        );
    }
}
