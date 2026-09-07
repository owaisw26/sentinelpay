CREATE TABLE reconciliation_runs (
    id UUID PRIMARY KEY,
    status VARCHAR(20) NOT NULL
        CHECK (status IN ('RUNNING', 'COMPLETED', 'FAILED')),
    cutoff_at TIMESTAMP NOT NULL,
    started_at TIMESTAMP NOT NULL,
    completed_at TIMESTAMP,
    scanned_count INTEGER NOT NULL DEFAULT 0 CHECK (scanned_count >= 0),
    discrepancy_count INTEGER NOT NULL DEFAULT 0
        CHECK (discrepancy_count >= 0),
    failure_reason VARCHAR(1000),
    CONSTRAINT chk_reconciliation_run_completion CHECK (
        (status = 'RUNNING' AND completed_at IS NULL)
        OR (status IN ('COMPLETED', 'FAILED') AND completed_at IS NOT NULL)
    ),
    CONSTRAINT chk_reconciliation_run_failure CHECK (
        (status = 'FAILED' AND failure_reason IS NOT NULL)
        OR (status <> 'FAILED' AND failure_reason IS NULL)
    )
);

CREATE INDEX idx_reconciliation_runs_started
    ON reconciliation_runs(started_at DESC, id DESC);

CREATE TABLE reconciliation_discrepancies (
    id UUID PRIMARY KEY,
    run_id UUID REFERENCES reconciliation_runs(id),
    payment_id UUID NOT NULL REFERENCES payments(id),
    type VARCHAR(50) NOT NULL CHECK (type IN (
        'MISSING_SUCCESS', 'MISSING_DECLINE', 'PROVIDER_PENDING',
        'PROVIDER_PAYMENT_NOT_FOUND', 'CONTRADICTORY_PROVIDER_STATUS'
    )),
    status VARCHAR(20) NOT NULL
        CHECK (status IN ('OPEN', 'RESOLVED', 'IGNORED')),
    local_payment_status VARCHAR(20) NOT NULL CHECK (local_payment_status IN (
        'CREATED', 'SCREENING', 'APPROVED', 'PROCESSING',
        'SETTLED', 'FAILED', 'HELD', 'BLOCKED'
    )),
    provider_status VARCHAR(20) NOT NULL CHECK (provider_status IN (
        'PENDING', 'SUCCEEDED', 'DECLINED', 'NOT_FOUND'
    )),
    recommended_action VARCHAR(20) NOT NULL
        CHECK (recommended_action IN ('CAPTURE', 'RELEASE', 'IGNORE')),
    detected_at TIMESTAMP NOT NULL,
    last_observed_at TIMESTAMP NOT NULL,
    resolved_at TIMESTAMP,
    resolved_by VARCHAR(255),
    resolution_action VARCHAR(20)
        CHECK (resolution_action IN ('CAPTURE', 'RELEASE', 'IGNORE')),
    resolution_reason VARCHAR(500),
    resolution_idempotency_key VARCHAR(128),
    resolution_provider_status VARCHAR(20) CHECK (
        resolution_provider_status IN (
            'PENDING', 'SUCCEEDED', 'DECLINED', 'NOT_FOUND'
        )
    ),
    version INTEGER NOT NULL DEFAULT 0 CHECK (version >= 0),
    CONSTRAINT chk_reconciliation_resolution_reason CHECK (
        resolution_reason IS NULL
        OR char_length(resolution_reason) BETWEEN 1 AND 500
    ),
    CONSTRAINT chk_reconciliation_resolution_key CHECK (
        resolution_idempotency_key IS NULL
        OR char_length(resolution_idempotency_key) BETWEEN 1 AND 128
    ),
    CONSTRAINT chk_reconciliation_discrepancy_resolution CHECK (
        (status = 'OPEN' AND resolved_at IS NULL AND resolved_by IS NULL
            AND resolution_action IS NULL AND resolution_reason IS NULL
            AND resolution_idempotency_key IS NULL
            AND resolution_provider_status IS NULL)
        OR
        (status IN ('RESOLVED', 'IGNORED') AND resolved_at IS NOT NULL
            AND resolved_by IS NOT NULL AND resolution_action IS NOT NULL
            AND resolution_reason IS NOT NULL
            AND resolution_idempotency_key IS NOT NULL
            AND resolution_provider_status IS NOT NULL)
    )
);

CREATE UNIQUE INDEX idx_reconciliation_one_open_per_payment
    ON reconciliation_discrepancies(payment_id)
    WHERE status = 'OPEN';

CREATE UNIQUE INDEX idx_reconciliation_resolution_idempotency
    ON reconciliation_discrepancies(resolved_by, resolution_idempotency_key)
    WHERE resolution_idempotency_key IS NOT NULL;

CREATE INDEX idx_reconciliation_discrepancies_page
    ON reconciliation_discrepancies(status, detected_at DESC, id DESC);

CREATE TABLE reconciliation_audit_records (
    id UUID PRIMARY KEY,
    run_id UUID REFERENCES reconciliation_runs(id),
    discrepancy_id UUID REFERENCES reconciliation_discrepancies(id),
    payment_id UUID REFERENCES payments(id),
    action VARCHAR(50) NOT NULL,
    actor_id VARCHAR(255) NOT NULL,
    details VARCHAR(2000) NOT NULL,
    occurred_at TIMESTAMP NOT NULL
);

CREATE INDEX idx_reconciliation_audit_discrepancy
    ON reconciliation_audit_records(discrepancy_id, occurred_at, id);

CREATE INDEX idx_reconciliation_audit_payment
    ON reconciliation_audit_records(payment_id, occurred_at, id);

CREATE FUNCTION prevent_reconciliation_audit_mutation()
RETURNS TRIGGER AS $$
BEGIN
    RAISE EXCEPTION 'reconciliation audit records are append-only';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER reconciliation_audit_append_only
BEFORE UPDATE OR DELETE ON reconciliation_audit_records
FOR EACH ROW EXECUTE FUNCTION prevent_reconciliation_audit_mutation();

ALTER TABLE webhook_receipts
    ADD COLUMN delivery_count INTEGER NOT NULL DEFAULT 1
        CHECK (delivery_count > 0),
    ADD COLUMN last_received_at TIMESTAMP;

UPDATE webhook_receipts
SET last_received_at = received_at
WHERE last_received_at IS NULL;

ALTER TABLE webhook_receipts
    ALTER COLUMN last_received_at SET NOT NULL;
