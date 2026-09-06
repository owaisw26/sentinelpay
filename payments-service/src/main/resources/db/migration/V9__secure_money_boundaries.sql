ALTER TABLE payments
    ALTER COLUMN idempotency_key TYPE VARCHAR(128)
        USING idempotency_key::text,
    DROP CONSTRAINT payments_idempotency_key_key,
    ADD CONSTRAINT chk_payments_currency_aud CHECK (currency = 'AUD'),
    ADD CONSTRAINT chk_payments_reference_length
        CHECK (char_length(reference) BETWEEN 1 AND 140),
    ADD CONSTRAINT chk_payments_status CHECK (
        status IN (
            'CREATED', 'SCREENING', 'APPROVED', 'PROCESSING',
            'SETTLED', 'FAILED', 'HELD', 'BLOCKED'
        )
    );

ALTER TABLE wallets
    ADD COLUMN version INTEGER NOT NULL DEFAULT 0,
    ADD CONSTRAINT chk_wallets_currency_aud CHECK (currency = 'AUD');

ALTER TABLE payment_reservations
    ADD CONSTRAINT chk_payment_reservations_currency_aud
        CHECK (currency = 'AUD');

CREATE INDEX idx_payments_sender_created
    ON payments(sender_wallet_id, created_at DESC, id DESC);

CREATE INDEX idx_payments_receiver_created
    ON payments(receiver_wallet_id, created_at DESC, id DESC);

CREATE INDEX idx_payments_status_updated
    ON payments(status, updated_at, id);

CREATE TABLE api_idempotency_records (
    principal_id UUID NOT NULL,
    route VARCHAR(100) NOT NULL,
    idempotency_key VARCHAR(128) NOT NULL,
    request_hash VARCHAR(64) NOT NULL,
    payment_id UUID REFERENCES payments(id),
    response_status INTEGER,
    response_body JSONB,
    created_at TIMESTAMP NOT NULL,
    completed_at TIMESTAMP,
    PRIMARY KEY (principal_id, route, idempotency_key),
    CONSTRAINT chk_api_idempotency_key_length
        CHECK (char_length(idempotency_key) BETWEEN 1 AND 128),
    CONSTRAINT chk_api_idempotency_completion CHECK (
        (payment_id IS NULL AND response_status IS NULL AND
            response_body IS NULL AND completed_at IS NULL)
        OR
        (payment_id IS NOT NULL AND response_status IS NOT NULL AND
            response_body IS NOT NULL AND completed_at IS NOT NULL)
    )
);

CREATE UNIQUE INDEX idx_api_idempotency_payment
    ON api_idempotency_records(payment_id)
    WHERE payment_id IS NOT NULL;

CREATE TABLE webhook_receipts (
    provider_event_id UUID PRIMARY KEY,
    provider_payment_id VARCHAR(255) NOT NULL,
    event_status VARCHAR(20) NOT NULL,
    payload_sha256 VARCHAR(64) NOT NULL,
    received_at TIMESTAMP NOT NULL,
    processed_at TIMESTAMP,
    CONSTRAINT chk_webhook_receipt_status
        CHECK (event_status IN ('SUCCEEDED', 'DECLINED'))
);

CREATE INDEX idx_webhook_receipts_provider_payment
    ON webhook_receipts(provider_payment_id, received_at DESC);
