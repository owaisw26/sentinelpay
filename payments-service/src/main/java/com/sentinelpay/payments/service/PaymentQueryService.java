package com.sentinelpay.payments.service;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.sentinelpay.payments.controller.response.PaymentPageResponse;
import com.sentinelpay.payments.controller.response.PaymentResponse;
import com.sentinelpay.payments.exception.InvalidPaymentRequestException;
import com.sentinelpay.payments.repository.PaymentRepository;

@Service
public class PaymentQueryService {
    private final PaymentRepository paymentRepository;

    public PaymentQueryService(PaymentRepository paymentRepository) {
        this.paymentRepository = paymentRepository;
    }

    @Transactional(readOnly = true)
    public PaymentPageResponse listForCustomer(
        UUID userId,
        String encodedCursor,
        int limit
    ) {
        Cursor cursor = decode(encodedCursor);
        var page = PageRequest.of(0, limit + 1);
        var found = cursor == null
            ? paymentRepository.findCustomerPayments(userId, page)
            : paymentRepository.findCustomerPaymentsAfter(
                userId, cursor.createdAt(), cursor.id(), page
            );
        boolean hasNext = found.size() > limit;
        List<PaymentResponse> items = found.stream()
            .limit(limit)
            .map(PaymentResponse::from)
            .toList();
        String nextCursor = hasNext
            ? encode(found.get(limit - 1).getCreatedAt(), found.get(limit - 1).getId())
            : null;
        return new PaymentPageResponse(items, nextCursor);
    }

    static String encode(LocalDateTime createdAt, UUID id) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(
            (createdAt + "|" + id).getBytes(StandardCharsets.UTF_8)
        );
    }

    static Cursor decode(String encoded) {
        if (encoded == null || encoded.isBlank()) {
            return null;
        }
        try {
            String decoded = new String(
                Base64.getUrlDecoder().decode(encoded), StandardCharsets.UTF_8
            );
            String[] parts = decoded.split("\\|", 2);
            if (parts.length != 2) {
                throw new IllegalArgumentException();
            }
            return new Cursor(LocalDateTime.parse(parts[0]), UUID.fromString(parts[1]));
        } catch (RuntimeException exception) {
            throw new InvalidPaymentRequestException("Payment cursor is invalid");
        }
    }

    record Cursor(LocalDateTime createdAt, UUID id) {}
}
