package com.sentinelpay.payments.repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import com.sentinelpay.payments.service.risk.RiskDecisionEnvelope;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

@Repository
public class RiskDecisionInboxRepository {
    public enum ClaimResult { NEW, DUPLICATE }

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public RiskDecisionInboxRepository(JdbcTemplate jdbcTemplate,
        ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    public ClaimResult claim(RiskDecisionEnvelope envelope, String payloadHash) {
        var payload = envelope.payload();
        int inserted = jdbcTemplate.update("""
            INSERT INTO risk_decision_inbox(
                event_id, decision_id, payment_id, source_event_id,
                source_aggregate_sequence, payload_sha256, received_at,
                score, reason_codes, feature_version, ruleset_version,
                model_version, matched_rule_ids
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, CAST(? AS jsonb), ?, ?, ?,
                CAST(? AS jsonb))
            ON CONFLICT DO NOTHING
            """,
            envelope.eventId(), payload.decisionId(), payload.paymentId(),
            payload.sourceEventId(), payload.sourceAggregateSequence(),
            payloadHash, OffsetDateTime.now(ZoneOffset.UTC), payload.score(),
            objectMapper.writeValueAsString(payload.reasonCodes()),
            payload.featureVersion(), payload.rulesetVersion(),
            payload.modelVersion(),
            objectMapper.writeValueAsString(payload.matchedRuleIds())
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

    public Optional<RiskDecisionSummary> findAppliedByPaymentId(UUID paymentId) {
        List<RiskDecisionSummary> found = jdbcTemplate.query("""
            SELECT decision_id, score, reason_codes::text AS reason_codes,
                   feature_version, ruleset_version, model_version,
                   matched_rule_ids::text AS matched_rule_ids, received_at
            FROM risk_decision_inbox
            WHERE payment_id = ? AND outcome LIKE 'APPLIED_%'
            ORDER BY received_at DESC
            LIMIT 1
            """,
            (resultSet, rowNumber) -> new RiskDecisionSummary(
                resultSet.getObject("decision_id", UUID.class),
                resultSet.getInt("score"),
                readStrings(resultSet.getString("reason_codes")),
                resultSet.getString("feature_version"),
                resultSet.getString("ruleset_version"),
                resultSet.getString("model_version"),
                readUuids(resultSet.getString("matched_rule_ids")),
                resultSet.getObject("received_at", OffsetDateTime.class)
            ),
            paymentId
        );
        return found.stream().findFirst();
    }

    private List<String> readStrings(String json) {
        return objectMapper.readValue(json, new TypeReference<List<String>>() {});
    }

    private List<UUID> readUuids(String json) {
        return objectMapper.readValue(json, new TypeReference<List<UUID>>() {});
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
