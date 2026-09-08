package com.sentinelpay.payments.service;

import org.springframework.stereotype.Component;

import com.sentinelpay.payments.service.PayeeNameMatcher.MatchResult;

@Component
public class LocalPayeeNameVerificationGateway
    implements PayeeNameVerificationGateway {
    private final PayeeNameMatcher nameMatcher;

    public LocalPayeeNameVerificationGateway(PayeeNameMatcher nameMatcher) {
        this.nameMatcher = nameMatcher;
    }

    @Override
    public MatchResult verify(String canonicalSuppliedName,
        String registeredName) {
        return nameMatcher.match(canonicalSuppliedName, registeredName);
    }
}
