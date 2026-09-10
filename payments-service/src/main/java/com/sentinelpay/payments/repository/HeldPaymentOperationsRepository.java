package com.sentinelpay.payments.repository;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import com.sentinelpay.payments.controller.response.HeldPaymentAuditResponse;
import com.sentinelpay.payments.domain.HeldPaymentAction;
import com.sentinelpay.payments.exception.HeldPaymentConflictException;

import tools.jackson.databind.ObjectMapper;

@Repository
public class HeldPaymentOperationsRepository {
    public enum ClaimResult { NEW, REPLAY }

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public HeldPaymentOperationsRepository(
        JdbcTemplate jdbcTemplate,
        ObjectMapper objectMapper
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    public ClaimResult claim(
        String actorId,
        String idempotencyKey,
        UUID paymentId,
        HeldPaymentAction action,
        String requestHash
    ) {
        int inserted = jdbcTemplate.update("""
            INSERT INTO held_payment_actions(
                actor_id, idempotency_key, payment_id, action,
                request_hash, created_at
            ) VALUES (?, ?, ?, ?, ?, ?)
            ON CONFLICT DO NOTHING
            """,
            actorId, idempotencyKey, paymentId, action.name(), requestHash,
            OffsetDateTime.now(ZoneOffset.UTC)
        );
        if (inserted == 1) {
            return ClaimResult.NEW;
        }

        List<ActionIdentity> existing = jdbcTemplate.query("""
            SELECT payment_id, action, request_hash, completed_at
            FROM held_payment_actions
            WHERE actor_id = ? AND idempotency_key = ?
            FOR UPDATE
            """,
            (resultSet, rowNumber) -> new ActionIdentity(
                resultSet.getObject("payment_id", UUID.class),
                resultSet.getString("action"),
                resultSet.getString("request_hash"),
                resultSet.getObject("completed_at", OffsetDateTime.class)
            ),
            actorId, idempotencyKey
        );
        if (existing.size() != 1 || !existing.getFirst().matches(
            paymentId, action, requestHash
        )) {
            throw new HeldPaymentConflictException(
                "Idempotency-Key was already used with another held decision"
            );
        }
        if (existing.getFirst().completedAt() == null) {
            throw new HeldPaymentConflictException("Held decision is still in progress");
        }
        return ClaimResult.REPLAY;
    }

    public void complete(String actorId, String idempotencyKey, String status) {
        int updated = jdbcTemplate.update("""
            UPDATE held_payment_actions
            SET response_status = ?, completed_at = CURRENT_TIMESTAMP
            WHERE actor_id = ? AND idempotency_key = ?
              AND completed_at IS NULL
            """, status, actorId, idempotencyKey);
        if (updated != 1) {
            throw new HeldPaymentConflictException("Held decision claim is not active");
        }
    }

    public void appendAudit(
        UUID paymentId,
        String action,
        String actorId,
        String reason,
        Object details
    ) {
        jdbcTemplate.update("""
            INSERT INTO held_payment_audit(
                audit_id, payment_id, action, actor_id, reason, details,
                occurred_at
            ) VALUES (?, ?, ?, ?, ?, CAST(? AS jsonb), ?)
            """,
            UUID.randomUUID(), paymentId, action, actorId, reason,
            objectMapper.writeValueAsString(details),
            OffsetDateTime.now(ZoneOffset.UTC)
        );
    }

    public List<HeldPaymentAuditResponse> findAudit(UUID paymentId) {
        return jdbcTemplate.query("""
            SELECT audit_id, payment_id, action, actor_id, reason,
                   details::text AS details, occurred_at
            FROM held_payment_audit
            WHERE payment_id = ?
            ORDER BY occurred_at, audit_id
            """,
            (resultSet, rowNumber) -> new HeldPaymentAuditResponse(
                resultSet.getObject("audit_id", UUID.class),
                resultSet.getObject("payment_id", UUID.class),
                resultSet.getString("action"),
                resultSet.getString("actor_id"),
                resultSet.getString("reason"),
                objectMapper.readTree(resultSet.getString("details")),
                resultSet.getObject("occurred_at", OffsetDateTime.class)
            ),
            paymentId
        );
    }

    private record ActionIdentity(
        UUID paymentId,
        String action,
        String requestHash,
        OffsetDateTime completedAt
    ) {
        boolean matches(
            UUID expectedPaymentId,
            HeldPaymentAction expectedAction,
            String expectedHash
        ) {
            return paymentId.equals(expectedPaymentId)
                && action.equals(expectedAction.name())
                && requestHash.equals(expectedHash);
        }
    }
}
