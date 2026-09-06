package com.sentinelpay.payments.repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class ApiIdempotencyRepository {
    private final JdbcTemplate jdbcTemplate;

    public ApiIdempotencyRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public boolean claim(UUID principalId, String route, String key,
        String requestHash, LocalDateTime now) {
        return jdbcTemplate.update(
            """
            INSERT INTO api_idempotency_records(
                principal_id, route, idempotency_key, request_hash, created_at
            ) VALUES (?, ?, ?, ?, ?)
            ON CONFLICT (principal_id, route, idempotency_key) DO NOTHING
            """,
            principalId, route, key, requestHash, now
        ) == 1;
    }

    public Optional<StoredResponse> find(UUID principalId, String route,
        String key) {
        return jdbcTemplate.query(
            """
            SELECT request_hash, payment_id, response_status,
                   response_body::text AS response_body
            FROM api_idempotency_records
            WHERE principal_id = ? AND route = ? AND idempotency_key = ?
            """,
            ApiIdempotencyRepository::mapStoredResponse,
            principalId, route, key
        ).stream().findFirst();
    }

    public void complete(UUID principalId, String route, String key,
        UUID paymentId, int responseStatus, String responseBody,
        LocalDateTime now) {
        int updated = jdbcTemplate.update(
            """
            UPDATE api_idempotency_records
            SET payment_id = ?, response_status = ?,
                response_body = CAST(? AS jsonb), completed_at = ?
            WHERE principal_id = ? AND route = ? AND idempotency_key = ?
              AND payment_id IS NULL
            """,
            paymentId, responseStatus, responseBody, now,
            principalId, route, key
        );
        if (updated != 1) {
            throw new IllegalStateException("Idempotency claim could not be completed");
        }
    }

    private static StoredResponse mapStoredResponse(ResultSet resultSet,
        int rowNumber) throws SQLException {
        return new StoredResponse(
            resultSet.getString("request_hash"),
            resultSet.getObject("payment_id", UUID.class),
            (Integer) resultSet.getObject("response_status"),
            resultSet.getString("response_body")
        );
    }

    public record StoredResponse(String requestHash, UUID paymentId,
        Integer responseStatus, String responseBody) {
        public boolean isComplete() {
            return paymentId != null && responseStatus != null &&
                responseBody != null;
        }
    }
}
