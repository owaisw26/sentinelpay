ALTER TABLE payments
    ADD COLUMN screening_sequence BIGINT,
    ADD COLUMN risk_decision_id UUID,
    ADD COLUMN risk_decision_action VARCHAR(20),
    ADD CONSTRAINT chk_payments_screening_sequence
        CHECK (screening_sequence IS NULL OR screening_sequence > 0),
    ADD CONSTRAINT chk_payments_risk_action
        CHECK (risk_decision_action IS NULL OR risk_decision_action IN (
            'APPROVE', 'HOLD', 'BLOCK'
        )),
    ADD CONSTRAINT chk_payments_risk_identity CHECK (
        (risk_decision_id IS NULL AND risk_decision_action IS NULL)
        OR
        (risk_decision_id IS NOT NULL AND risk_decision_action IS NOT NULL)
    );

ALTER TABLE outbox_events
    ADD COLUMN schema_version INTEGER NOT NULL DEFAULT 1
        CHECK (schema_version > 0),
    ADD COLUMN aggregate_sequence BIGINT NOT NULL DEFAULT 1
        CHECK (aggregate_sequence > 0),
    ADD COLUMN occurred_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    ADD COLUMN causation_id UUID;

CREATE UNIQUE INDEX idx_outbox_aggregate_event_sequence
    ON outbox_events(aggregate_id, event_type, aggregate_sequence);

CREATE TABLE risk_decision_inbox (
    event_id UUID PRIMARY KEY,
    decision_id UUID NOT NULL UNIQUE,
    payment_id UUID NOT NULL REFERENCES payments(id),
    source_event_id UUID NOT NULL,
    source_aggregate_sequence BIGINT NOT NULL
        CHECK (source_aggregate_sequence > 0),
    payload_sha256 VARCHAR(64) NOT NULL,
    received_at TIMESTAMPTZ NOT NULL,
    processed_at TIMESTAMPTZ,
    outcome VARCHAR(30) CHECK (outcome IN (
        'APPLIED_APPROVE', 'APPLIED_HOLD', 'APPLIED_BLOCK',
        'IGNORED_DUPLICATE', 'IGNORED_STALE', 'IGNORED_TERMINAL'
    )),
    CONSTRAINT chk_risk_inbox_processing CHECK (
        (processed_at IS NULL AND outcome IS NULL)
        OR (processed_at IS NOT NULL AND outcome IS NOT NULL)
    )
);

CREATE INDEX idx_risk_inbox_payment_received
    ON risk_decision_inbox(payment_id, received_at DESC);
