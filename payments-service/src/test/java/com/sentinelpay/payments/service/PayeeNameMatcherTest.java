package com.sentinelpay.payments.service;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.stream.Stream;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import com.sentinelpay.payments.domain.PayeeCheckOutcome;

class PayeeNameMatcherTest {
    private final PayeeNameMatcher matcher = new PayeeNameMatcher();

    @ParameterizedTest(name = "{0} compared with {1} is {2}")
    @MethodSource("fixtures")
    void calibratedFixturesRemainStable(String supplied, String registered,
        PayeeCheckOutcome expected) {
        assertEquals(expected, matcher.match(supplied, registered).outcome());
    }

    private static Stream<Arguments> fixtures() {
        return Stream.of(
            Arguments.of(
                "JOSE O'CONNOR", "Jos\u00e9 O\u2019Connor",
                PayeeCheckOutcome.MATCH
            ),
            Arguments.of(
                "Harbour Labs Pty. Ltd.",
                "Harbour Labs Proprietary Limited",
                PayeeCheckOutcome.MATCH
            ),
            Arguments.of(
                "Anne-Marie Chen", "anne marie chen",
                PayeeCheckOutcome.MATCH
            ),
            Arguments.of(
                "J Smith", "John Smith",
                PayeeCheckOutcome.CLOSE_MATCH
            ),
            Arguments.of(
                "Jon Smith", "John Smith",
                PayeeCheckOutcome.CLOSE_MATCH
            ),
            Arguments.of(
                "Smith John", "John Smith",
                PayeeCheckOutcome.CLOSE_MATCH
            ),
            Arguments.of(
                "Jane Smythe", "John Smith",
                PayeeCheckOutcome.NO_MATCH
            ),
            Arguments.of(
                "Acme Pay", "Acme Pty Ltd",
                PayeeCheckOutcome.NO_MATCH
            ),
            Arguments.of(
                "---", "John Smith",
                PayeeCheckOutcome.NO_MATCH
            )
        );
    }
}
