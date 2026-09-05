package com.sentinelpay.payments.security;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HexFormat;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.junit.jupiter.api.Test;

import com.sentinelpay.payments.exception.InvalidWebhookSignatureException;

class WebhookSignatureVerifierTest {
    private static final String SECRET = "test-webhook-secret";

    private final WebhookSignatureVerifier verifier =
        new WebhookSignatureVerifier(SECRET, 300);

    @Test
    void acceptsSignatureForExactRawBody() {
        String timestamp = Long.toString(Instant.now().getEpochSecond());
        byte[] body = "{\"status\":\"SUCCEEDED\"}"
            .getBytes(StandardCharsets.UTF_8);

        assertDoesNotThrow(() -> verifier.verify(
            timestamp,
            "v1=" + signature(timestamp, body),
            body
        ));
    }

    @Test
    void rejectsBodyTampering() {
        String timestamp = Long.toString(Instant.now().getEpochSecond());
        byte[] signedBody = "{\"status\":\"SUCCEEDED\"}"
            .getBytes(StandardCharsets.UTF_8);
        byte[] forgedBody = "{\"status\":\"DECLINED\"}"
            .getBytes(StandardCharsets.UTF_8);

        assertThrows(
            InvalidWebhookSignatureException.class,
            () -> verifier.verify(
                timestamp,
                "v1=" + signature(timestamp, signedBody),
                forgedBody
            )
        );
    }

    @Test
    void rejectsReplayOutsideToleranceWindow() {
        String timestamp = Long.toString(
            Instant.now().minusSeconds(301).getEpochSecond()
        );
        byte[] body = "{}".getBytes(StandardCharsets.UTF_8);

        assertThrows(
            InvalidWebhookSignatureException.class,
            () -> verifier.verify(
                timestamp,
                "v1=" + signature(timestamp, body),
                body
            )
        );
    }

    private String signature(String timestamp, byte[] body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(
                SECRET.getBytes(StandardCharsets.UTF_8),
                "HmacSHA256"
            ));
            mac.update(timestamp.getBytes(StandardCharsets.UTF_8));
            mac.update((byte) '.');
            return HexFormat.of().formatHex(mac.doFinal(body));
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }
}
