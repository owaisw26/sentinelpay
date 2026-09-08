CREATE TABLE payee_registry_entries (
    id UUID PRIMARY KEY,
    receiver_wallet_id UUID NOT NULL REFERENCES wallets(id),
    registry_version INTEGER NOT NULL CHECK (registry_version > 0),
    legal_name VARCHAR(200) NOT NULL
        CHECK (char_length(legal_name) BETWEEN 1 AND 200),
    active BOOLEAN NOT NULL,
    created_at TIMESTAMP NOT NULL,
    UNIQUE (receiver_wallet_id, registry_version)
);

CREATE UNIQUE INDEX idx_payee_registry_active_wallet
    ON payee_registry_entries(receiver_wallet_id)
    WHERE active;

CREATE TABLE payee_checks (
    id UUID PRIMARY KEY,
    requester_user_id UUID NOT NULL REFERENCES users(user_id),
    receiver_wallet_id UUID NOT NULL REFERENCES wallets(id),
    registry_version INTEGER NOT NULL,
    supplied_name_hash VARCHAR(64) NOT NULL
        CHECK (char_length(supplied_name_hash) = 64),
    outcome VARCHAR(20) NOT NULL
        CHECK (outcome IN ('MATCH', 'CLOSE_MATCH', 'NO_MATCH')),
    reason_code VARCHAR(40) NOT NULL
        CHECK (reason_code IN (
            'NAME_MATCHED', 'NAME_SIMILAR', 'NAME_NOT_MATCHED'
        )),
    created_at TIMESTAMP NOT NULL,
    expires_at TIMESTAMP NOT NULL,
    consumed_at TIMESTAMP,
    FOREIGN KEY (receiver_wallet_id, registry_version)
        REFERENCES payee_registry_entries(receiver_wallet_id, registry_version),
    CONSTRAINT chk_payee_check_expiry CHECK (expires_at > created_at),
    CONSTRAINT chk_payee_check_consumption CHECK (
        consumed_at IS NULL OR consumed_at >= created_at
    )
);

CREATE INDEX idx_payee_checks_requester_expiry
    ON payee_checks(requester_user_id, expires_at DESC);

CREATE INDEX idx_payee_checks_receiver_expiry
    ON payee_checks(receiver_wallet_id, expires_at DESC);

INSERT INTO payee_registry_entries (
    id, receiver_wallet_id, registry_version, legal_name, active, created_at
)
SELECT w.id, w.id, 1,
       left(COALESCE(NULLIF(trim(u.name), ''), 'Synthetic Payee'), 200),
       TRUE, w.created_at
FROM wallets w
JOIN users u ON u.user_id = w.user_id;

INSERT INTO payee_checks (
    id, requester_user_id, receiver_wallet_id, registry_version,
    supplied_name_hash, outcome, reason_code, created_at, expires_at,
    consumed_at
)
SELECT p.id, sw.user_id, p.receiver_wallet_id, 1, repeat('0', 64),
       'MATCH', 'NAME_MATCHED', p.created_at,
       p.created_at + INTERVAL '15 minutes', NULL
FROM payments p
JOIN wallets sw ON sw.id = p.sender_wallet_id;

ALTER TABLE payments
    ADD COLUMN payee_check_id UUID,
    ADD COLUMN accepted_name_mismatch BOOLEAN NOT NULL DEFAULT FALSE;

UPDATE payments SET payee_check_id = id;

ALTER TABLE payments
    ALTER COLUMN payee_check_id SET NOT NULL,
    ADD CONSTRAINT fk_payments_payee_check
        FOREIGN KEY (payee_check_id) REFERENCES payee_checks(id);

CREATE INDEX idx_payments_payee_check ON payments(payee_check_id);
