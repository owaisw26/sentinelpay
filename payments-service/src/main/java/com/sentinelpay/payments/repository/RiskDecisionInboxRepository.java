package com.sentinelpay.payments.repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import com.sentinelpay.payments.service.risk.RiskDecisionEnvelope;

@Repository
public class RiskDecisionInboxRepository {
    public enum ClaimResult { NEW, DUPLICATE }

    private final JdbcTemplate jdbcTemplate;

    public RiskDecisionInboxRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public ClaimResult claim(RiskDecisionEnvelope envelope, String payloadHash) {
        var payload = envelope.payload();
        int inserted = jdbcTemplate.update("""
            INSERT INTO risk_decision_inbox(
                event_id, decision_id, payment_id, source_event_id,
                source_aggregate_sequence, payload_sha256, received_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT DO NOTHING
            """,
            envelope.eventId(), payload.decisionId(), payload.paymentId(),
            payload.sourceEventId(), payload.sourceAggregateSequence(),
            payloadHash, OffsetDateTime.now(ZoneOffset.UTC)
        );
        if (inserted == 1) {
            return ClaimResult.NEW;
        }

        List<InboxIdentity> matches = jdbcTemplate.query("""
            SELECT event_id, decision_id, payment_id, source_event_id,
                   source_aggregate_sequence, payload_sha256, processed_at
            FROM risk_decision_inbox
            WHERE event_id = ? OR decision_id = ?
            FOR UPDATE
            """,
            (resultSet, rowNumber) -> mapIdentity(resultSet),
            envelope.eventId(), payload.decisionId()
        );
        if (matches.size() != 1 || !matches.getFirst().matches(
            envelope, payloadHash
        )) {
            throw new IllegalStateException(
                "Conflicting risk decision identity or payload"
            );
        }
        return ClaimResult.DUPLICATE;
    }

    public void markProcessed(UUID eventId, String outcome) {
        int updated = jdbcTemplate.update("""
            UPDATE risk_decision_inbox
            SET processed_at = CURRENT_TIMESTAMP, outcome = ?
            WHERE event_id = ? AND processed_at IS NULL
            """, outcome, eventId);
        if (updated != 1) {
            throw new IllegalStateException("Risk inbox claim was not active");
        }
    }

    private InboxIdentity mapIdentity(ResultSet resultSet) throws SQLException {
        return new InboxIdentity(
            resultSet.getObject("event_id", UUID.class),
            resultSet.getObject("decision_id", UUID.class),
            resultSet.getObject("payment_id", UUID.class),
            resultSet.getObject("source_event_id", UUID.class),
            resultSet.getLong("source_aggregate_sequence"),
            resultSet.getString("payload_sha256")
        );
    }

    private record InboxIdentity(
        UUID eventId,
        UUID decisionId,
        UUID paymentId,
        UUID sourceEventId,
        long sourceAggregateSequence,
        String payloadHash
    ) {
        boolean matches(RiskDecisionEnvelope envelope, String expectedHash) {
            var payload = envelope.payload();
            return eventId.equals(envelope.eventId())
                && decisionId.equals(payload.decisionId())
                && paymentId.equals(payload.paymentId())
                && sourceEventId.equals(payload.sourceEventId())
                && sourceAggregateSequence == payload.sourceAggregateSequence()
                && payloadHash.equals(expectedHash);
        }
    }
}
