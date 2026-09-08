package com.sentinelpay.payments.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;

import com.sentinelpay.payments.domain.PayeeCheck;
import com.sentinelpay.payments.domain.PayeeCheckOutcome;
import com.sentinelpay.payments.domain.PayeeCheckReason;
import com.sentinelpay.payments.domain.PayeeCheckVerificationSource;
import com.sentinelpay.payments.domain.PayeeRegistryEntry;
import com.sentinelpay.payments.domain.User;
import com.sentinelpay.payments.domain.Wallet;
import com.sentinelpay.payments.exception.PayeeVerificationUnavailableException;
import com.sentinelpay.payments.repository.PayeeCheckRepository;
import com.sentinelpay.payments.repository.PayeeRegistryRepository;
import com.sentinelpay.payments.repository.WalletRepository;
import com.sentinelpay.payments.security.PayloadHasher;
import com.sentinelpay.payments.service.PayeeNameMatcher.MatchResult;

class PayeeCheckDegradationTest {
    private final PayeeCheckRepository checkRepository =
        mock(PayeeCheckRepository.class);
    private final PayeeRegistryRepository registryRepository =
        mock(PayeeRegistryRepository.class);
    private final WalletRepository walletRepository =
        mock(WalletRepository.class);
    private final PayeeNameMatcher matcher = mock(PayeeNameMatcher.class);
    private final PayeeNameVerificationGateway gateway =
        mock(PayeeNameVerificationGateway.class);
    private final RateLimitService rateLimitService =
        mock(RateLimitService.class);
    private final PayeeNameCheckCache cache = new PayeeNameCheckCache(
        10, Duration.ofMinutes(5)
    );

    private PayeeCheckService service;
    private UUID requesterId;
    private UUID receiverId;
    private PayeeRegistryEntry registryEntry;

    @BeforeEach
    void setUp() {
        requesterId = UUID.randomUUID();
        receiverId = UUID.randomUUID();
        User receiverUser = new User(
            UUID.randomUUID(), "Alice Example", "CUSTOMER", LocalDateTime.now()
        );
        Wallet receiver = new Wallet(
            receiverId, receiverUser, "AUD", BigDecimal.ZERO,
            LocalDateTime.now()
        );
        registryEntry = new PayeeRegistryEntry(
            UUID.randomUUID(), receiverId, 3, "Alice Example", true,
            LocalDateTime.now()
        );
        when(walletRepository.findByIdForUpdate(receiverId))
            .thenReturn(Optional.of(receiver));
        when(registryRepository.findByReceiverWalletIdAndActiveTrue(receiverId))
            .thenReturn(Optional.of(registryEntry));
        when(matcher.canonicalize("Alice Example"))
            .thenReturn("alice example");
        when(checkRepository.saveAndFlush(any(PayeeCheck.class)))
            .thenAnswer(invocation -> invocation.getArgument(0));
        service = new PayeeCheckService(
            checkRepository, registryRepository, walletRepository, matcher,
            gateway, cache, rateLimitService, Duration.ofMinutes(15),
            Duration.ofHours(24)
        );
    }

    @Test
    void firstTimePayeeFailsClosedWhenVerifierIsUnavailable() {
        when(gateway.verify("alice example", "Alice Example"))
            .thenThrow(new PayeeVerificationUnavailableException());
        when(checkRepository
            .findRecentSuccessfulChecks(
                eq(requesterId), eq(receiverId), eq(3),
                eq(PayloadHasher.sha256("alice example")),
                eq(PayeeCheckOutcome.MATCH), any(LocalDateTime.class),
                any(Pageable.class)
            )).thenReturn(java.util.List.of());

        assertThrows(
            PayeeVerificationUnavailableException.class,
            () -> service.createCheck(
                requesterId, receiverId, "Alice Example"
            )
        );
        verify(checkRepository, never()).saveAndFlush(any(PayeeCheck.class));
    }

    @Test
    void exactRecentSuccessfulRelationshipCanBeReusedForDegradation() {
        when(gateway.verify("alice example", "Alice Example"))
            .thenThrow(new PayeeVerificationUnavailableException());
        PayeeCheck previous = new PayeeCheck(
            UUID.randomUUID(), requesterId, receiverId, 3,
            PayloadHasher.sha256("alice example"), PayeeCheckOutcome.MATCH,
            PayeeCheckReason.NAME_MATCHED, LocalDateTime.now().minusHours(1),
            LocalDateTime.now().minusMinutes(45),
            PayeeCheckVerificationSource.DIRECT
        );
        when(checkRepository
            .findRecentSuccessfulChecks(
                eq(requesterId), eq(receiverId), eq(3),
                eq(PayloadHasher.sha256("alice example")),
                eq(PayeeCheckOutcome.MATCH), any(LocalDateTime.class),
                any(Pageable.class)
            )).thenReturn(java.util.List.of(previous));

        PayeeCheck result = service.createCheck(
            requesterId, receiverId, "Alice Example"
        );

        assertEquals(PayeeCheckOutcome.MATCH, result.getOutcome());
        assertEquals(
            PayeeCheckVerificationSource.DEGRADED_REUSE,
            result.getVerificationSource()
        );
    }

    @Test
    void cacheHitAvoidsVerifierAndRetainsCacheProvenance() {
        cache.put(
            new PayeeNameCheckCache.Key(3, receiverId, "alice example"),
            new MatchResult(
                PayeeCheckOutcome.MATCH, PayeeCheckReason.NAME_MATCHED
            )
        );

        PayeeCheck result = service.createCheck(
            requesterId, receiverId, "Alice Example"
        );

        assertEquals(
            PayeeCheckVerificationSource.CACHE,
            result.getVerificationSource()
        );
        verify(gateway, never()).verify(any(), any());
    }
}
