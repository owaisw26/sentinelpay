package com.sentinelpay.payments.service.risk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.ObjectMapper;

class RiskDecisionMessageParserTest {
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final RiskDecisionMessageParser parser =
        new RiskDecisionMessageParser(objectMapper);

    @Test
    void acceptsDirectAndSnsWrappedDecisionEnvelopes() {
        RiskDecisionEnvelope envelope = decision();
        String direct = objectMapper.writeValueAsString(envelope);
        String wrapped = objectMapper.writeValueAsString(java.util.Map.of(
            "Type", "Notification",
            "Message", direct
        ));

        assertEquals(envelope, parser.parse(direct));
        assertEquals(envelope, parser.parse(wrapped));
    }

    @Test
    void rejectsUnknownContractVersions() {
        RiskDecisionEnvelope valid = decision();
        RiskDecisionEnvelope incompatible = new RiskDecisionEnvelope(
            valid.eventId(), valid.eventType(), 2, valid.aggregateId(),
            valid.aggregateSequence(), valid.occurredAt(),
            valid.correlationId(), valid.causationId(), valid.payload()
        );

        assertThrows(
            IllegalArgumentException.class,
            () -> parser.parse(objectMapper.writeValueAsString(incompatible))
        );
    }

    private RiskDecisionEnvelope decision() {
        UUID paymentId = UUID.randomUUID();
        UUID sourceEventId = UUID.randomUUID();
        UUID decisionId = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        return new RiskDecisionEnvelope(
            decisionId, "RISK_DECISION_MADE", 1, paymentId, 1, now,
            UUID.randomUUID(), sourceEventId,
            new RiskDecisionPayload(
                decisionId, paymentId, sourceEventId, 1,
                "payment-features-v1", "deterministic-rules-v1", "none",
                0, RiskAction.APPROVE, List.of(), List.of(), now
            )
        );
    }
}
