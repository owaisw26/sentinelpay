package com.sentinelpay.payments.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;

import com.github.benmanes.caffeine.cache.Caffeine;
import com.sentinelpay.payments.domain.PayeeCheckOutcome;
import com.sentinelpay.payments.domain.PayeeCheckReason;
import com.sentinelpay.payments.service.PayeeNameMatcher.MatchResult;

class PayeeNameCheckCacheTest {
    @Test
    void entriesExpireAndReceiverInvalidationRemovesAllVersions() {
        AtomicLong ticker = new AtomicLong();
        var caffeine = Caffeine.newBuilder()
            .maximumSize(10)
            .expireAfterWrite(Duration.ofMinutes(5))
            .ticker(ticker::get)
            .<PayeeNameCheckCache.Key, MatchResult>build();
        PayeeNameCheckCache cache = new PayeeNameCheckCache(caffeine);
        UUID receiver = UUID.randomUUID();
        MatchResult result = new MatchResult(
            PayeeCheckOutcome.MATCH, PayeeCheckReason.NAME_MATCHED
        );
        PayeeNameCheckCache.Key versionOne = new PayeeNameCheckCache.Key(
            1, receiver, "alice example"
        );
        PayeeNameCheckCache.Key versionTwo = new PayeeNameCheckCache.Key(
            2, receiver, "alice example"
        );

        cache.put(versionOne, result);
        assertEquals(result, cache.get(versionOne));
        ticker.addAndGet(Duration.ofMinutes(5).toNanos());
        assertNull(cache.get(versionOne));

        cache.put(versionOne, result);
        cache.put(versionTwo, result);
        cache.invalidateReceiver(receiver);
        assertNull(cache.get(versionOne));
        assertNull(cache.get(versionTwo));
    }
}
