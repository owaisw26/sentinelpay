ALTER TABLE fraud.risk_decisions
    ADD COLUMN IF NOT EXISTS matched_rule_ids UUID[] NOT NULL DEFAULT '{}';

CREATE TABLE IF NOT EXISTS fraud.rule_proposals (
    proposal_id UUID PRIMARY KEY,
    status VARCHAR(30) NOT NULL CHECK (
        status IN ('DRAFT', 'APPROVED', 'REJECTED', 'VALIDATION_FAILED')
    ),
    version BIGINT NOT NULL DEFAULT 0 CHECK (version >= 0),
    prompt_version VARCHAR(100) NOT NULL,
    schema_version VARCHAR(100) NOT NULL,
    model VARCHAR(100) NOT NULL,
    provider_response_id VARCHAR(255) NOT NULL,
    response_sha256 VARCHAR(64) NOT NULL CHECK (
        response_sha256 ~ '^[a-f0-9]{64}$'
    ),
    input_summary JSONB NOT NULL,
    candidate JSONB,
    impact JSONB,
    usage JSONB NOT NULL,
    validation_failures JSONB NOT NULL,
    generated_by VARCHAR(255) NOT NULL,
    generated_at TIMESTAMPTZ NOT NULL,
    reviewed_by VARCHAR(255),
    reviewed_at TIMESTAMPTZ,
    review_reason VARCHAR(500),
    approved_ruleset_version BIGINT,
    CONSTRAINT chk_rule_proposal_candidate
        CHECK ((status = 'VALIDATION_FAILED') OR candidate IS NOT NULL),
    CONSTRAINT chk_rule_proposal_review
        CHECK (
            (status IN ('DRAFT', 'VALIDATION_FAILED') AND reviewed_at IS NULL)
            OR
            (status IN ('APPROVED', 'REJECTED') AND reviewed_at IS NOT NULL)
        )
);

CREATE TABLE IF NOT EXISTS fraud.rule_sets (
    version BIGINT PRIMARY KEY CHECK (version >= 0),
    parent_version BIGINT REFERENCES fraud.rule_sets(version),
    operation VARCHAR(30) NOT NULL CHECK (
        operation IN ('INITIAL', 'APPROVE', 'DEACTIVATE', 'ROLLBACK')
    ),
    rules JSONB NOT NULL,
    source_proposal_id UUID REFERENCES fraud.rule_proposals(proposal_id),
    created_by VARCHAR(255) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    reason VARCHAR(500) NOT NULL
);

INSERT INTO fraud.rule_sets(
    version, parent_version, operation, rules, source_proposal_id,
    created_by, created_at, reason
) VALUES (
    0, NULL, 'INITIAL', '[]'::jsonb, NULL,
    'system', TIMESTAMPTZ '2026-01-01 00:00:00+00',
    'Initial empty dynamic ruleset'
) ON CONFLICT (version) DO NOTHING;

CREATE TABLE IF NOT EXISTS fraud.active_rule_set (
    singleton BOOLEAN PRIMARY KEY DEFAULT TRUE CHECK (singleton),
    version BIGINT NOT NULL REFERENCES fraud.rule_sets(version),
    activated_at TIMESTAMPTZ NOT NULL,
    activated_by VARCHAR(255) NOT NULL
);

INSERT INTO fraud.active_rule_set(singleton, version, activated_at, activated_by)
VALUES (TRUE, 0, TIMESTAMPTZ '2026-01-01 00:00:00+00', 'system')
ON CONFLICT (singleton) DO NOTHING;

CREATE TABLE IF NOT EXISTS fraud.rule_audit (
    audit_id UUID PRIMARY KEY,
    event_type VARCHAR(40) NOT NULL,
    proposal_id UUID,
    ruleset_version BIGINT,
    actor VARCHAR(255) NOT NULL,
    reason VARCHAR(500) NOT NULL,
    details JSONB NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_rule_proposals_status_generated
    ON fraud.rule_proposals(status, generated_at DESC, proposal_id);

CREATE INDEX IF NOT EXISTS idx_rule_audit_proposal_time
    ON fraud.rule_audit(proposal_id, occurred_at, audit_id);

DROP TRIGGER IF EXISTS fraud_rule_sets_append_only ON fraud.rule_sets;
CREATE TRIGGER fraud_rule_sets_append_only
BEFORE UPDATE OR DELETE ON fraud.rule_sets
FOR EACH ROW EXECUTE FUNCTION fraud.prevent_immutable_stream_mutation();

DROP TRIGGER IF EXISTS fraud_rule_audit_append_only ON fraud.rule_audit;
CREATE TRIGGER fraud_rule_audit_append_only
BEFORE UPDATE OR DELETE ON fraud.rule_audit
FOR EACH ROW EXECUTE FUNCTION fraud.prevent_immutable_stream_mutation();

GRANT SELECT, INSERT, UPDATE ON fraud.rule_proposals TO fraud_app;
GRANT SELECT, INSERT ON fraud.rule_sets TO fraud_app;
GRANT SELECT, UPDATE ON fraud.active_rule_set TO fraud_app;
GRANT SELECT, INSERT ON fraud.rule_audit TO fraud_app;
