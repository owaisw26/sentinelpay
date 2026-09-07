package com.sentinelpay.payments.controller.response;

import java.time.LocalDateTime;
import java.util.UUID;

import com.sentinelpay.payments.domain.PaymentStatus;
import com.sentinelpay.payments.domain.ReconciliationAction;
import com.sentinelpay.payments.domain.ReconciliationDiscrepancy;
import com.sentinelpay.payments.domain.ReconciliationDiscrepancyStatus;
import com.sentinelpay.payments.domain.ReconciliationDiscrepancyType;
import com.sentinelpay.payments.provider.PaymentProviderLookupStatus;

public record ReconciliationDiscrepancyResponse(
    UUID discrepancyId,
    UUID paymentId,
    ReconciliationDiscrepancyType type,
    ReconciliationDiscrepancyStatus status,
    PaymentStatus localPaymentStatus,
    PaymentProviderLookupStatus providerStatus,
    ReconciliationAction recommendedAction,
    LocalDateTime detectedAt,
    LocalDateTime lastObservedAt,
    int version,
    LocalDateTime resolvedAt,
    String resolvedBy,
    ReconciliationAction resolutionAction,
    String resolutionReason
) {
    public static ReconciliationDiscrepancyResponse from(
        ReconciliationDiscrepancy discrepancy
    ) {
        return new ReconciliationDiscrepancyResponse(
            discrepancy.getId(),
            discrepancy.getPaymentId(),
            discrepancy.getType(),
            discrepancy.getStatus(),
            discrepancy.getLocalPaymentStatus(),
            discrepancy.getProviderStatus(),
            discrepancy.getRecommendedAction(),
            discrepancy.getDetectedAt(),
            discrepancy.getLastObservedAt(),
            discrepancy.getVersion(),
            discrepancy.getResolvedAt(),
            discrepancy.getResolvedBy(),
            discrepancy.getResolutionAction(),
            discrepancy.getResolutionReason()
        );
    }
}
