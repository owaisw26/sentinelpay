package com.sentinelpay.payments.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.sentinelpay.payments.exception.InvalidWebhookSignatureException;

@Component
public class WebhookSignatureVerifier {
    private static final String ALGORITHM = "HmacSHA256";

    private final byte[] secret;
    private final long toleranceSeconds;

    public WebhookSignatureVerifier(
        @Value("${sentinelpay.psp.webhook-secret}") String secret,
        @Value("${sentinelpay.psp.webhook-tolerance-seconds:300}")
        long toleranceSeconds
    ) {
        if (secret == null || secret.isBlank()) {
            throw new IllegalArgumentException("Webhook secret must not be blank");
        }
        if (toleranceSeconds <= 0) {
            throw new IllegalArgumentException(
                "Webhook timestamp tolerance must be positive"
            );
        }
        this.secret = secret.getBytes(StandardCharsets.UTF_8);
        this.toleranceSeconds = toleranceSeconds;
    }

    public void verify(String timestampHeader, String signatureHeader, byte[] body) {
        Instant timestamp;
        try {
            timestamp = Instant.ofEpochSecond(Long.parseLong(timestampHeader));
        } catch (RuntimeException exception) {
            throw new InvalidWebhookSignatureException();
        }

        Instant now = Instant.now();
        if (timestamp.isBefore(now.minusSeconds(toleranceSeconds)) ||
            timestamp.isAfter(now.plusSeconds(toleranceSeconds)) ||
            signatureHeader == null ||
            !signatureHeader.startsWith("v1=")) {
            throw new InvalidWebhookSignatureException();
        }

        byte[] suppliedSignature;
        try {
            suppliedSignature = HexFormat.of().parseHex(
                signatureHeader.substring("v1=".length())
            );
        } catch (IllegalArgumentException exception) {
            throw new InvalidWebhookSignatureException();
        }

        byte[] expectedSignature = sign(timestampHeader, body);
        if (!MessageDigest.isEqual(expectedSignature, suppliedSignature)) {
            throw new InvalidWebhookSignatureException();
        }
    }

    private byte[] sign(String timestamp, byte[] body) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(secret, ALGORITHM));
            mac.update(timestamp.getBytes(StandardCharsets.UTF_8));
            mac.update((byte) '.');
            return mac.doFinal(body);
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to verify webhook signature", exception);
        }
    }
}
