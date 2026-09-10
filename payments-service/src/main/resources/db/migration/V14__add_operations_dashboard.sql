ALTER TABLE users
    ADD COLUMN external_subject VARCHAR(255);

UPDATE users
SET external_subject = user_id::text
WHERE external_subject IS NULL;

ALTER TABLE users
    ALTER COLUMN external_subject SET NOT NULL,
    ADD CONSTRAINT uq_users_external_subject UNIQUE (external_subject);

ALTER TABLE risk_decision_inbox
    ADD COLUMN score INTEGER,
    ADD COLUMN reason_codes JSONB,
    ADD COLUMN feature_version VARCHAR(100),
    ADD COLUMN ruleset_version VARCHAR(100),
    ADD COLUMN model_version VARCHAR(100),
    ADD COLUMN matched_rule_ids JSONB;

UPDATE risk_decision_inbox
SET score = 0,
    reason_codes = '[]'::jsonb,
    feature_version = 'legacy',
    ruleset_version = 'legacy',
    model_version = 'legacy',
    matched_rule_ids = '[]'::jsonb
WHERE score IS NULL;

ALTER TABLE risk_decision_inbox
    ALTER COLUMN score SET NOT NULL,
    ALTER COLUMN reason_codes SET NOT NULL,
    ALTER COLUMN feature_version SET NOT NULL,
    ALTER COLUMN ruleset_version SET NOT NULL,
    ALTER COLUMN model_version SET NOT NULL,
    ALTER COLUMN matched_rule_ids SET NOT NULL,
    ADD CONSTRAINT chk_risk_inbox_score CHECK (score >= 0),
    ADD CONSTRAINT chk_risk_inbox_reason_codes_array
        CHECK (jsonb_typeof(reason_codes) = 'array'),
    ADD CONSTRAINT chk_risk_inbox_rule_ids_array
        CHECK (jsonb_typeof(matched_rule_ids) = 'array');

CREATE TABLE held_payment_actions (
    actor_id VARCHAR(255) NOT NULL,
    idempotency_key VARCHAR(128) NOT NULL,
    payment_id UUID NOT NULL REFERENCES payments(id),
    action VARCHAR(20) NOT NULL CHECK (action IN ('APPROVE', 'BLOCK')),
    request_hash VARCHAR(64) NOT NULL,
    response_status VARCHAR(20),
    created_at TIMESTAMPTZ NOT NULL,
    completed_at TIMESTAMPTZ,
    PRIMARY KEY (actor_id, idempotency_key),
    CONSTRAINT chk_held_action_key_length
        CHECK (char_length(idempotency_key) BETWEEN 1 AND 128),
    CONSTRAINT chk_held_action_completion CHECK (
        (response_status IS NULL AND completed_at IS NULL)
        OR
        (response_status IS NOT NULL AND completed_at IS NOT NULL)
    )
);

CREATE INDEX idx_held_payment_actions_payment
    ON held_payment_actions(payment_id, created_at DESC);

CREATE TABLE held_payment_audit (
    audit_id UUID PRIMARY KEY,
    payment_id UUID NOT NULL REFERENCES payments(id),
    action VARCHAR(30) NOT NULL CHECK (
        action IN ('VIEWED', 'LISTED', 'APPROVED', 'BLOCKED')
    ),
    actor_id VARCHAR(255) NOT NULL,
    reason VARCHAR(500),
    details JSONB NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX idx_held_payment_audit_payment_time
    ON held_payment_audit(payment_id, occurred_at, audit_id);

CREATE OR REPLACE FUNCTION prevent_held_payment_audit_mutation()
RETURNS TRIGGER AS $$
BEGIN
    RAISE EXCEPTION 'held payment audit is append-only';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER held_payment_audit_append_only
BEFORE UPDATE OR DELETE ON held_payment_audit
FOR EACH ROW EXECUTE FUNCTION prevent_held_payment_audit_mutation();
