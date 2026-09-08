package com.sentinelpay.payments.service;

import java.time.Duration;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.sentinelpay.payments.service.PayeeNameMatcher.MatchResult;

@Component
public class PayeeNameCheckCache {
    private final Cache<Key, MatchResult> cache;

    @Autowired
    public PayeeNameCheckCache(
        @Value("${sentinelpay.payee-check.cache.maximum-size:10000}")
        long maximumSize,
        @Value("${sentinelpay.payee-check.cache.ttl:PT5M}") Duration ttl
    ) {
        if (maximumSize < 1 || ttl.isZero() || ttl.isNegative()) {
            throw new IllegalArgumentException(
                "NameCheck cache size and TTL must be positive"
            );
        }
        this.cache = Caffeine.newBuilder()
            .maximumSize(maximumSize)
            .expireAfterWrite(ttl)
            .build();
    }

    PayeeNameCheckCache(Cache<Key, MatchResult> cache) {
        this.cache = cache;
    }

    public MatchResult get(Key key) {
        return cache.getIfPresent(key);
    }

    public void put(Key key, MatchResult result) {
        cache.put(key, result);
    }

    public void invalidateReceiver(UUID receiverWalletId) {
        cache.asMap().keySet().removeIf(
            key -> key.receiverWalletId().equals(receiverWalletId)
        );
    }

    public record Key(
        int registryVersion,
        UUID receiverWalletId,
        String canonicalSuppliedName
    ) {}
}
