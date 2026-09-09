package com.sentinelpay.payments.service.risk;

import org.springframework.stereotype.Component;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Component
public class RiskDecisionMessageParser {
    private final ObjectMapper objectMapper;

    public RiskDecisionMessageParser(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public RiskDecisionEnvelope parse(String body) {
        JsonNode document = objectMapper.readTree(body);
        if (document == null || !document.isObject()) {
            throw new IllegalArgumentException(
                "Risk decision message must be a JSON object"
            );
        }
        if (document.has("Type")) {
            if (!"Notification".equals(document.path("Type").asText()) ||
                !document.path("Message").isTextual()) {
                throw new IllegalArgumentException("Unsupported SNS message");
            }
            document = objectMapper.readTree(document.path("Message").asText());
        }
        RiskDecisionEnvelope envelope = objectMapper.treeToValue(
            document, RiskDecisionEnvelope.class
        );
        if (envelope.schemaVersion() != 1 ||
            !"RISK_DECISION_MADE".equals(envelope.eventType())) {
            throw new IllegalArgumentException(
                "Unsupported risk decision contract"
            );
        }
        return envelope;
    }
}
