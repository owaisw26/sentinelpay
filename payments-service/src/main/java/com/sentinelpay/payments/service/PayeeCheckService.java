package com.sentinelpay.payments.service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import com.sentinelpay.payments.domain.PayeeCheck;
import com.sentinelpay.payments.domain.PayeeCheckOutcome;
import com.sentinelpay.payments.domain.PayeeCheckVerificationSource;
import com.sentinelpay.payments.domain.PayeeRegistryEntry;
import com.sentinelpay.payments.domain.Wallet;
import com.sentinelpay.payments.exception.InvalidPayeeCheckException;
import com.sentinelpay.payments.exception.InvalidPaymentRequestException;
import com.sentinelpay.payments.exception.PayeeVerificationUnavailableException;
import com.sentinelpay.payments.exception.WalletNotFoundException;
import com.sentinelpay.payments.repository.PayeeCheckRepository;
import com.sentinelpay.payments.repository.PayeeRegistryRepository;
import com.sentinelpay.payments.repository.WalletRepository;
import com.sentinelpay.payments.security.PayloadHasher;
import com.sentinelpay.payments.service.PayeeNameMatcher.MatchResult;

import jakarta.transaction.Transactional;

@Service
public class PayeeCheckService {
    private final PayeeCheckRepository payeeCheckRepository;
    private final PayeeRegistryRepository payeeRegistryRepository;
    private final WalletRepository walletRepository;
    private final PayeeNameMatcher nameMatcher;
    private final PayeeNameVerificationGateway verificationGateway;
    private final PayeeNameCheckCache nameCheckCache;
    private final RateLimitService rateLimitService;
    private final Duration checkTtl;
    private final Duration degradedReuseMaximumAge;

    public PayeeCheckService(
        PayeeCheckRepository payeeCheckRepository,
        PayeeRegistryRepository payeeRegistryRepository,
        WalletRepository walletRepository,
        PayeeNameMatcher nameMatcher,
        PayeeNameVerificationGateway verificationGateway,
        PayeeNameCheckCache nameCheckCache,
        RateLimitService rateLimitService,
        @Value("${sentinelpay.payee-check.ttl:PT15M}") Duration checkTtl,
        @Value("${sentinelpay.payee-check.degraded-reuse-max-age:PT24H}")
        Duration degradedReuseMaximumAge
    ) {
        this.payeeCheckRepository = payeeCheckRepository;
        this.payeeRegistryRepository = payeeRegistryRepository;
        this.walletRepository = walletRepository;
        this.nameMatcher = nameMatcher;
        this.verificationGateway = verificationGateway;
        this.nameCheckCache = nameCheckCache;
        this.rateLimitService = rateLimitService;
        if (checkTtl.isZero() || checkTtl.isNegative()
            || degradedReuseMaximumAge.isZero()
            || degradedReuseMaximumAge.isNegative()) {
            throw new IllegalArgumentException(
                "Payee check TTLs must be positive"
            );
        }
        this.checkTtl = checkTtl;
        this.degradedReuseMaximumAge = degradedReuseMaximumAge;
    }

    @Transactional
    public PayeeCheck createCheck(UUID requesterUserId, UUID receiverWalletId,
        String suppliedName) {
        rateLimitService.consume(
            requesterUserId, RateLimitOperation.PAYEE_CHECK
        );
        if (requesterUserId == null || receiverWalletId == null
            || suppliedName == null || suppliedName.isBlank()
            || suppliedName.length() > 200) {
            throw new InvalidPaymentRequestException(
                "Receiver wallet and a name of at most 200 characters are required"
            );
        }
        String canonicalSuppliedName = nameMatcher.canonicalize(suppliedName);
        if (canonicalSuppliedName.isBlank()) {
            throw new InvalidPaymentRequestException(
                "Supplied name must contain letters or digits"
            );
        }

        Wallet receiver = walletRepository.findByIdForUpdate(receiverWalletId)
            .orElseThrow(() -> new WalletNotFoundException(receiverWalletId));
        PayeeRegistryEntry registryEntry = payeeRegistryRepository
            .findByReceiverWalletIdAndActiveTrue(receiverWalletId)
            .orElseGet(() -> payeeRegistryRepository.saveAndFlush(
                new PayeeRegistryEntry(
                    UUID.randomUUID(), receiverWalletId, 1,
                    receiver.getUser().getName(), true, LocalDateTime.now()
                )
            ));

        LocalDateTime now = LocalDateTime.now();
        String suppliedNameHash = PayloadHasher.sha256(canonicalSuppliedName);
        PayeeNameCheckCache.Key cacheKey = new PayeeNameCheckCache.Key(
            registryEntry.getRegistryVersion(), receiverWalletId,
            canonicalSuppliedName
        );
        MatchResult match = nameCheckCache.get(cacheKey);
        PayeeCheckVerificationSource source = PayeeCheckVerificationSource.CACHE;
        if (match == null) {
            try {
                match = verificationGateway.verify(
                    canonicalSuppliedName, registryEntry.getLegalName()
                );
                nameCheckCache.put(cacheKey, match);
                source = PayeeCheckVerificationSource.DIRECT;
            } catch (PayeeVerificationUnavailableException exception) {
                match = reuseRecentSuccessfulCheck(
                    requesterUserId, receiverWalletId, registryEntry,
                    suppliedNameHash, now
                );
                source = PayeeCheckVerificationSource.DEGRADED_REUSE;
            }
        }
        return payeeCheckRepository.saveAndFlush(new PayeeCheck(
            UUID.randomUUID(), requesterUserId, receiverWalletId,
            registryEntry.getRegistryVersion(),
            suppliedNameHash, match.outcome(), match.reasonCode(), now,
            now.plus(checkTtl), source
        ));
    }

    @Transactional
    public PayeeCheck authorizeForPayment(UUID checkId, UUID requesterUserId,
        UUID receiverWalletId, boolean acceptNameMismatch) {
        if (checkId == null) {
            throw InvalidPayeeCheckException.invalid();
        }
        PayeeCheck check = payeeCheckRepository.findByIdForUpdate(checkId)
            .orElseThrow(InvalidPayeeCheckException::invalid);
        LocalDateTime now = LocalDateTime.now();
        if (!check.getRequesterUserId().equals(requesterUserId)
            || !check.getReceiverWalletId().equals(receiverWalletId)
            || check.isExpiredAt(now)
            || payeeRegistryRepository
                .findByReceiverWalletIdAndActiveTrue(receiverWalletId)
                .map(entry -> entry.getRegistryVersion()
                    != check.getRegistryVersion())
                .orElse(true)) {
            throw InvalidPayeeCheckException.invalid();
        }
        if (!check.isMismatch()) {
            return check;
        }
        if (!acceptNameMismatch) {
            throw InvalidPayeeCheckException.mismatchNotAccepted();
        }
        if (check.getConsumedAt() != null) {
            throw InvalidPayeeCheckException.invalid();
        }
        check.consumeMismatch(now);
        return check;
    }

    @Transactional
    public PayeeRegistryEntry replaceRegistryName(UUID receiverWalletId,
        String legalName) {
        if (receiverWalletId == null || legalName == null
            || legalName.isBlank() || legalName.length() > 200
            || nameMatcher.canonicalize(legalName).isBlank()) {
            throw new IllegalArgumentException("Registry name is invalid");
        }
        walletRepository.findByIdForUpdate(receiverWalletId)
            .orElseThrow(() -> new WalletNotFoundException(receiverWalletId));
        PayeeRegistryEntry current = payeeRegistryRepository
            .findByReceiverWalletIdAndActiveTrue(receiverWalletId)
            .orElseThrow(() -> new IllegalStateException(
                "Payee registry entry is missing"
            ));
        current.deactivate();
        payeeRegistryRepository.saveAndFlush(current);
        PayeeRegistryEntry replacement = payeeRegistryRepository.save(
            new PayeeRegistryEntry(
                UUID.randomUUID(), receiverWalletId,
                current.getRegistryVersion() + 1, legalName, true,
                LocalDateTime.now()
            )
        );
        nameCheckCache.invalidateReceiver(receiverWalletId);
        return replacement;
    }

    private MatchResult reuseRecentSuccessfulCheck(UUID requesterUserId,
        UUID receiverWalletId, PayeeRegistryEntry registryEntry,
        String suppliedNameHash, LocalDateTime now) {
        PayeeCheck previous = payeeCheckRepository
            .findRecentSuccessfulChecks(
            requesterUserId,
            receiverWalletId,
            registryEntry.getRegistryVersion(),
            suppliedNameHash,
            PayeeCheckOutcome.MATCH,
            now.minus(degradedReuseMaximumAge),
            PageRequest.ofSize(1)
        ).stream().findFirst()
            .orElseThrow(PayeeVerificationUnavailableException::new);
        return new MatchResult(previous.getOutcome(), previous.getReasonCode());
    }
}
