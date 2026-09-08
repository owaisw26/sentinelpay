package com.sentinelpay.payments.service;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import org.springframework.stereotype.Component;

import com.sentinelpay.payments.domain.PayeeCheckOutcome;
import com.sentinelpay.payments.domain.PayeeCheckReason;

@Component
public class PayeeNameMatcher {
    private static final Set<String> SINGLE_BUSINESS_SUFFIXES = Set.of(
        "limited", "ltd", "inc", "incorporated", "llc", "corp",
        "corporation", "company"
    );
    private static final List<List<String>> MULTI_BUSINESS_SUFFIXES = List.of(
        List.of("proprietary", "limited"),
        List.of("pty", "limited"),
        List.of("pty", "ltd")
    );

    public MatchResult match(String suppliedName, String registeredName) {
        String supplied = canonicalize(suppliedName);
        String registered = canonicalize(registeredName);

        if (supplied.isBlank() || registered.isBlank()) {
            return new MatchResult(
                PayeeCheckOutcome.NO_MATCH,
                PayeeCheckReason.NAME_NOT_MATCHED
            );
        }
        if (supplied.equals(registered)) {
            return new MatchResult(
                PayeeCheckOutcome.MATCH,
                PayeeCheckReason.NAME_MATCHED
            );
        }

        List<String> suppliedTokens = tokens(supplied);
        List<String> registeredTokens = tokens(registered);
        double similarity = Math.max(
            jaroWinkler(supplied, registered),
            jaroWinkler(sortedName(suppliedTokens), sortedName(registeredTokens))
        );
        similarity = Math.max(
            similarity,
            jaroWinkler(initials(suppliedTokens), initials(registeredTokens))
        );
        if (tokensAreCompatible(suppliedTokens, registeredTokens)
            && similarity >= 0.90d) {
            return new MatchResult(
                PayeeCheckOutcome.CLOSE_MATCH,
                PayeeCheckReason.NAME_SIMILAR
            );
        }
        return new MatchResult(
            PayeeCheckOutcome.NO_MATCH,
            PayeeCheckReason.NAME_NOT_MATCHED
        );
    }

    public String canonicalize(String name) {
        if (name == null) {
            return "";
        }
        String decomposed = Normalizer.normalize(name, Normalizer.Form.NFKD);
        StringBuilder normalized = new StringBuilder(decomposed.length());
        boolean pendingSpace = false;
        for (int offset = 0; offset < decomposed.length();) {
            int codePoint = decomposed.codePointAt(offset);
            offset += Character.charCount(codePoint);
            int type = Character.getType(codePoint);
            if (type == Character.NON_SPACING_MARK
                || type == Character.COMBINING_SPACING_MARK
                || type == Character.ENCLOSING_MARK) {
                continue;
            }
            if (Character.isLetterOrDigit(codePoint)) {
                if (pendingSpace && !normalized.isEmpty()) {
                    normalized.append(' ');
                }
                normalized.appendCodePoint(Character.toLowerCase(codePoint));
                pendingSpace = false;
            } else if (codePoint == '\'' || codePoint == '\u2019') {
                // Apostrophes do not split a token: O'Connor -> oconnor.
            } else {
                pendingSpace = true;
            }
        }

        List<String> nameTokens = new ArrayList<>(tokens(
            normalized.toString().toLowerCase(Locale.ROOT)
        ));
        stripBusinessSuffixes(nameTokens);
        return String.join(" ", nameTokens);
    }

    private void stripBusinessSuffixes(List<String> nameTokens) {
        boolean removed;
        do {
            removed = false;
            for (List<String> suffix : MULTI_BUSINESS_SUFFIXES) {
                if (endsWith(nameTokens, suffix)) {
                    nameTokens.subList(
                        nameTokens.size() - suffix.size(), nameTokens.size()
                    ).clear();
                    removed = true;
                    break;
                }
            }
            if (!removed && nameTokens.size() > 1
                && SINGLE_BUSINESS_SUFFIXES.contains(
                    nameTokens.getLast()
                )) {
                nameTokens.removeLast();
                removed = true;
            }
        } while (removed && !nameTokens.isEmpty());
    }

    private boolean endsWith(List<String> value, List<String> suffix) {
        return value.size() > suffix.size()
            && value.subList(value.size() - suffix.size(), value.size())
                .equals(suffix);
    }

    private boolean tokensAreCompatible(List<String> left, List<String> right) {
        if (left.size() != right.size()) {
            return false;
        }
        List<String> sortedLeft = left.stream()
            .sorted(Comparator.comparingInt(String::length).thenComparing(s -> s))
            .toList();
        List<String> unmatched = new ArrayList<>(right);
        for (String token : sortedLeft) {
            int matchIndex = -1;
            double bestScore = -1;
            for (int index = 0; index < unmatched.size(); index++) {
                String candidate = unmatched.get(index);
                double score = tokenCompatibility(token, candidate);
                if (score > bestScore) {
                    bestScore = score;
                    matchIndex = index;
                }
            }
            if (bestScore < 0.88d) {
                return false;
            }
            unmatched.remove(matchIndex);
        }
        return true;
    }

    private double tokenCompatibility(String left, String right) {
        if (left.equals(right)) {
            return 1.0d;
        }
        if ((left.length() == 1 || right.length() == 1)
            && left.charAt(0) == right.charAt(0)) {
            return 0.95d;
        }
        if (left.charAt(0) != right.charAt(0)) {
            return 0.0d;
        }
        return jaroWinkler(left, right);
    }

    private List<String> tokens(String canonicalName) {
        if (canonicalName == null || canonicalName.isBlank()) {
            return List.of();
        }
        return Arrays.asList(canonicalName.split(" "));
    }

    private String sortedName(List<String> value) {
        return value.stream().sorted().reduce(
            (left, right) -> left + " " + right
        ).orElse("");
    }

    private String initials(List<String> value) {
        return value.stream()
            .map(token -> token.substring(0, 1))
            .sorted()
            .reduce((left, right) -> left + " " + right)
            .orElse("");
    }

    static double jaroWinkler(String left, String right) {
        if (left.equals(right)) {
            return 1.0d;
        }
        if (left.isEmpty() || right.isEmpty()) {
            return 0.0d;
        }

        int matchDistance = Math.max(left.length(), right.length()) / 2 - 1;
        matchDistance = Math.max(0, matchDistance);
        boolean[] leftMatches = new boolean[left.length()];
        boolean[] rightMatches = new boolean[right.length()];
        int matches = 0;

        for (int leftIndex = 0; leftIndex < left.length(); leftIndex++) {
            int start = Math.max(0, leftIndex - matchDistance);
            int end = Math.min(leftIndex + matchDistance + 1, right.length());
            for (int rightIndex = start; rightIndex < end; rightIndex++) {
                if (rightMatches[rightIndex]
                    || left.charAt(leftIndex) != right.charAt(rightIndex)) {
                    continue;
                }
                leftMatches[leftIndex] = true;
                rightMatches[rightIndex] = true;
                matches++;
                break;
            }
        }
        if (matches == 0) {
            return 0.0d;
        }

        int transpositions = 0;
        int rightIndex = 0;
        for (int leftIndex = 0; leftIndex < left.length(); leftIndex++) {
            if (!leftMatches[leftIndex]) {
                continue;
            }
            while (!rightMatches[rightIndex]) {
                rightIndex++;
            }
            if (left.charAt(leftIndex) != right.charAt(rightIndex)) {
                transpositions++;
            }
            rightIndex++;
        }

        double jaro = (
            matches / (double) left.length()
            + matches / (double) right.length()
            + (matches - transpositions / 2.0d) / matches
        ) / 3.0d;
        int prefix = 0;
        int maxPrefix = Math.min(4, Math.min(left.length(), right.length()));
        while (prefix < maxPrefix
            && left.charAt(prefix) == right.charAt(prefix)) {
            prefix++;
        }
        return jaro + prefix * 0.1d * (1.0d - jaro);
    }

    public record MatchResult(
        PayeeCheckOutcome outcome,
        PayeeCheckReason reasonCode
    ) {}
}
