package com.sentinelpay.payments.controller.response;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

import com.sentinelpay.payments.domain.Payment;
import com.sentinelpay.payments.domain.PaymentStatus;
import com.sentinelpay.payments.repository.RiskDecisionSummary;

public record HeldPaymentResponse(
    UUID paymentId,
    UUID senderWalletId,
    UUID receiverWalletId,
    BigDecimal amount,
    String currency,
    String reference,
    PaymentStatus status,
    int version,
    LocalDateTime createdAt,
    LocalDateTime updatedAt,
    RiskExplanationResponse risk
) {
    public static HeldPaymentResponse from(
        Payment payment,
        RiskDecisionSummary risk
    ) {
        return new HeldPaymentResponse(
            payment.getId(), payment.getSenderWallet().getId(),
            payment.getReceiverWallet().getId(), payment.getAmount(),
            payment.getCurrency(), payment.getReference(), payment.getStatus(),
            payment.getVersion(), payment.getCreatedAt(), payment.getUpdatedAt(),
            RiskExplanationResponse.from(risk)
        );
    }
}
