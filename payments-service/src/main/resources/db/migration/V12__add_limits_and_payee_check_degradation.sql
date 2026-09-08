CREATE TABLE api_rate_limit_windows (
    subject_id UUID NOT NULL,
    operation VARCHAR(40) NOT NULL,
    window_started_at TIMESTAMP WITH TIME ZONE NOT NULL,
    request_count INTEGER NOT NULL CHECK (request_count > 0),
    PRIMARY KEY (subject_id, operation)
);

ALTER TABLE payee_checks
    ADD COLUMN verification_source VARCHAR(20) NOT NULL DEFAULT 'DIRECT'
        CHECK (verification_source IN ('DIRECT', 'CACHE', 'DEGRADED_REUSE'));

CREATE INDEX idx_payee_checks_degraded_reuse
    ON payee_checks (
        requester_user_id,
        receiver_wallet_id,
        registry_version,
        supplied_name_hash,
        created_at DESC
    )
    WHERE outcome = 'MATCH';
