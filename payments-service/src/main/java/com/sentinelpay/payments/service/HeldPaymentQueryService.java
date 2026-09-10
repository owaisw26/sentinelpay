package com.sentinelpay.payments.service;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.sentinelpay.payments.controller.response.HeldPaymentAuditResponse;
import com.sentinelpay.payments.controller.response.HeldPaymentPageResponse;
import com.sentinelpay.payments.controller.response.HeldPaymentResponse;
import com.sentinelpay.payments.domain.Payment;
import com.sentinelpay.payments.domain.PaymentStatus;
import com.sentinelpay.payments.exception.HeldPaymentNotFoundException;
import com.sentinelpay.payments.exception.InvalidPaymentRequestException;
import com.sentinelpay.payments.repository.HeldPaymentOperationsRepository;
import com.sentinelpay.payments.repository.PaymentRepository;
import com.sentinelpay.payments.repository.RiskDecisionInboxRepository;

@Service
public class HeldPaymentQueryService {
    private final PaymentRepository paymentRepository;
    private final RiskDecisionInboxRepository riskDecisions;
    private final HeldPaymentOperationsRepository operations;

    public HeldPaymentQueryService(
        PaymentRepository paymentRepository,
        RiskDecisionInboxRepository riskDecisions,
        HeldPaymentOperationsRepository operations
    ) {
        this.paymentRepository = paymentRepository;
        this.riskDecisions = riskDecisions;
        this.operations = operations;
    }

    @Transactional
    public HeldPaymentPageResponse list(
        String encodedCursor,
        int limit,
        String actorId
    ) {
        Cursor cursor = decode(encodedCursor);
        var page = PageRequest.of(0, limit + 1);
        var found = cursor == null
            ? paymentRepository.findHeldPayments(page)
            : paymentRepository.findHeldPaymentsAfter(
                cursor.updatedAt(), cursor.id(), page
            );
        boolean hasNext = found.size() > limit;
        List<HeldPaymentResponse> items = found.stream()
            .limit(limit)
            .map(payment -> {
                operations.appendAudit(
                    payment.getId(), "LISTED", actorId, null,
                    Map.of("status", payment.getStatus().name())
                );
                return response(payment);
            })
            .toList();
        String nextCursor = hasNext
            ? encode(found.get(limit - 1).getUpdatedAt(), found.get(limit - 1).getId())
            : null;
        return new HeldPaymentPageResponse(items, nextCursor);
    }

    @Transactional
    public HeldPaymentResponse get(UUID paymentId, String actorId) {
        Payment payment = heldPayment(paymentId);
        operations.appendAudit(
            paymentId, "VIEWED", actorId, null,
            Map.of("version", payment.getVersion())
        );
        return response(payment);
    }

    @Transactional
    public List<HeldPaymentAuditResponse> audit(UUID paymentId, String actorId) {
        if (!paymentRepository.existsById(paymentId)) {
            throw new HeldPaymentNotFoundException();
        }
        operations.appendAudit(paymentId, "VIEWED", actorId, null,
            Map.of("resource", "AUDIT_TRAIL"));
        return operations.findAudit(paymentId);
    }

    HeldPaymentResponse response(Payment payment) {
        var risk = riskDecisions.findAppliedByPaymentId(payment.getId())
            .orElseThrow(() -> new IllegalStateException(
                "Held payment is missing its applied risk decision"
            ));
        return HeldPaymentResponse.from(payment, risk);
    }

    private Payment heldPayment(UUID paymentId) {
        Payment payment = paymentRepository.findById(paymentId)
            .orElseThrow(HeldPaymentNotFoundException::new);
        if (payment.getStatus() != PaymentStatus.HELD) {
            throw new HeldPaymentNotFoundException();
        }
        return payment;
    }

    private static String encode(LocalDateTime updatedAt, UUID id) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(
            (updatedAt + "|" + id).getBytes(StandardCharsets.UTF_8)
        );
    }

    private static Cursor decode(String encoded) {
        if (encoded == null || encoded.isBlank()) {
            return null;
        }
        try {
            String value = new String(
                Base64.getUrlDecoder().decode(encoded), StandardCharsets.UTF_8
            );
            String[] parts = value.split("\\|", 2);
            if (parts.length != 2) {
                throw new IllegalArgumentException();
            }
            return new Cursor(LocalDateTime.parse(parts[0]), UUID.fromString(parts[1]));
        } catch (RuntimeException exception) {
            throw new InvalidPaymentRequestException("Held-payment cursor is invalid");
        }
    }

    private record Cursor(LocalDateTime updatedAt, UUID id) {}
}
