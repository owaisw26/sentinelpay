DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'fraud_app') THEN
        CREATE ROLE fraud_app NOLOGIN;
    END IF;
END
$$;

CREATE SCHEMA IF NOT EXISTS fraud;
REVOKE ALL ON SCHEMA fraud FROM PUBLIC;
GRANT USAGE ON SCHEMA fraud TO fraud_app;

CREATE TABLE IF NOT EXISTS fraud.received_events (
    event_id UUID PRIMARY KEY,
    event_type VARCHAR(100) NOT NULL,
    schema_version INTEGER NOT NULL CHECK (schema_version > 0),
    aggregate_id UUID NOT NULL,
    aggregate_sequence BIGINT NOT NULL CHECK (aggregate_sequence > 0),
    customer_id UUID NOT NULL,
    payment_id UUID NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL,
    received_at TIMESTAMPTZ NOT NULL,
    decision_due_at TIMESTAMPTZ NOT NULL,
    is_late BOOLEAN NOT NULL,
    envelope JSONB NOT NULL,
    envelope_sha256 VARCHAR(64) NOT NULL,
    CONSTRAINT uq_fraud_received_aggregate_sequence
        UNIQUE (aggregate_id, event_type, aggregate_sequence),
    CONSTRAINT uq_fraud_received_payment UNIQUE (payment_id),
    CONSTRAINT chk_fraud_received_due
        CHECK (decision_due_at >= occurred_at)
);

CREATE INDEX IF NOT EXISTS idx_fraud_received_due
    ON fraud.received_events(decision_due_at, event_id);

CREATE INDEX IF NOT EXISTS idx_fraud_received_customer_event_time
    ON fraud.received_events(customer_id, occurred_at, event_id);

CREATE TABLE IF NOT EXISTS fraud.customer_watermarks (
    customer_id UUID PRIMARY KEY,
    max_occurred_at_seen TIMESTAMPTZ NOT NULL,
    finalized_through TIMESTAMPTZ,
    updated_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE IF NOT EXISTS fraud.risk_features (
    decision_id UUID PRIMARY KEY,
    source_event_id UUID NOT NULL UNIQUE
        REFERENCES fraud.received_events(event_id),
    feature_version VARCHAR(100) NOT NULL,
    features JSONB NOT NULL,
    computed_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE IF NOT EXISTS fraud.risk_decisions (
    decision_id UUID PRIMARY KEY,
    payment_id UUID NOT NULL,
    source_event_id UUID NOT NULL UNIQUE
        REFERENCES fraud.received_events(event_id),
    source_aggregate_sequence BIGINT NOT NULL,
    feature_version VARCHAR(100) NOT NULL,
    ruleset_version VARCHAR(100) NOT NULL,
    model_version VARCHAR(100) NOT NULL,
    score INTEGER NOT NULL CHECK (score >= 0),
    action VARCHAR(20) NOT NULL CHECK (action IN ('APPROVE', 'HOLD', 'BLOCK')),
    reason_codes TEXT[] NOT NULL,
    decision JSONB NOT NULL,
    decided_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_fraud_decision_payment_sequence
        UNIQUE (payment_id, source_aggregate_sequence)
);

CREATE TABLE IF NOT EXISTS fraud.decision_outbox (
    event_id UUID PRIMARY KEY
        REFERENCES fraud.risk_decisions(decision_id),
    payload JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    published_at TIMESTAMPTZ,
    lease_token UUID,
    lease_until TIMESTAMPTZ,
    attempt_count INTEGER NOT NULL DEFAULT 0 CHECK (attempt_count >= 0),
    next_attempt_at TIMESTAMPTZ NOT NULL,
    last_error VARCHAR(1000)
);

CREATE INDEX IF NOT EXISTS idx_fraud_decision_outbox_pending
    ON fraud.decision_outbox(next_attempt_at, created_at, event_id)
    WHERE published_at IS NULL;

CREATE OR REPLACE FUNCTION fraud.prevent_immutable_stream_mutation()
RETURNS TRIGGER AS $$
BEGIN
    RAISE EXCEPTION '% is append-only', TG_TABLE_NAME;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS fraud_received_events_append_only
    ON fraud.received_events;
CREATE TRIGGER fraud_received_events_append_only
BEFORE UPDATE OR DELETE ON fraud.received_events
FOR EACH ROW EXECUTE FUNCTION fraud.prevent_immutable_stream_mutation();

DROP TRIGGER IF EXISTS fraud_risk_features_append_only
    ON fraud.risk_features;
CREATE TRIGGER fraud_risk_features_append_only
BEFORE UPDATE OR DELETE ON fraud.risk_features
FOR EACH ROW EXECUTE FUNCTION fraud.prevent_immutable_stream_mutation();

DROP TRIGGER IF EXISTS fraud_risk_decisions_append_only
    ON fraud.risk_decisions;
CREATE TRIGGER fraud_risk_decisions_append_only
BEFORE UPDATE OR DELETE ON fraud.risk_decisions
FOR EACH ROW EXECUTE FUNCTION fraud.prevent_immutable_stream_mutation();

GRANT SELECT, INSERT ON fraud.received_events TO fraud_app;
GRANT SELECT, INSERT, UPDATE ON fraud.customer_watermarks TO fraud_app;
GRANT SELECT, INSERT ON fraud.risk_features TO fraud_app;
GRANT SELECT, INSERT ON fraud.risk_decisions TO fraud_app;
GRANT SELECT, INSERT, UPDATE ON fraud.decision_outbox TO fraud_app;

ALTER DEFAULT PRIVILEGES IN SCHEMA fraud
    GRANT SELECT, INSERT ON TABLES TO fraud_app;
