package com.sentinelpay.payments.service;

import java.time.Duration;
import java.util.EnumMap;
import java.util.Map;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.sentinelpay.payments.exception.RateLimitExceededException;
import com.sentinelpay.payments.repository.PostgresFixedWindowRateLimitRepository;

@Service
public class RateLimitService {
    private final PostgresFixedWindowRateLimitRepository repository;
    private final Map<RateLimitOperation, Integer> maximumRequests;
    private final long windowSeconds;

    public RateLimitService(
        PostgresFixedWindowRateLimitRepository repository,
        @Value("${sentinelpay.rate-limit.payee-checks.max-requests:20}")
        int payeeCheckMaximum,
        @Value("${sentinelpay.rate-limit.payment-creation.max-requests:10}")
        int paymentCreationMaximum,
        @Value("${sentinelpay.rate-limit.window:PT1M}") Duration window
    ) {
        if (payeeCheckMaximum < 1 || paymentCreationMaximum < 1
            || window.isZero() || window.isNegative()
            || window.toSeconds() < 1) {
            throw new IllegalArgumentException(
                "Rate-limit maxima and window must be positive"
            );
        }
        this.repository = repository;
        this.maximumRequests = new EnumMap<>(RateLimitOperation.class);
        this.maximumRequests.put(
            RateLimitOperation.PAYEE_CHECK, payeeCheckMaximum
        );
        this.maximumRequests.put(
            RateLimitOperation.PAYMENT_CREATE, paymentCreationMaximum
        );
        this.windowSeconds = window.toSeconds();
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void consume(UUID subjectId, RateLimitOperation operation) {
        if (subjectId == null) {
            throw new IllegalArgumentException("Rate-limit subject is required");
        }
        int maximum = maximumRequests.get(operation);
        var result = repository.increment(
            subjectId, operation, maximum, windowSeconds
        );
        if (!result.allowed()) {
            throw new RateLimitExceededException(result.retryAfterSeconds());
        }
    }
}
