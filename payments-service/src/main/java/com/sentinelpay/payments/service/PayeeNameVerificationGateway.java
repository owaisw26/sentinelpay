package com.sentinelpay.payments.service;

import com.sentinelpay.payments.service.PayeeNameMatcher.MatchResult;

public interface PayeeNameVerificationGateway {
    MatchResult verify(String canonicalSuppliedName, String registeredName);
}
