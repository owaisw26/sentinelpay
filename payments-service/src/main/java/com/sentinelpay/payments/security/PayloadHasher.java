package com.sentinelpay.payments.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

public final class PayloadHasher {
    private PayloadHasher() {}

    public static String sha256(byte[] payload) {
        try {
            return HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(payload)
            );
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    public static String sha256(String payload) {
        return sha256(payload.getBytes(StandardCharsets.UTF_8));
    }
}
