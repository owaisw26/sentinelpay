ALTER TABLE wallets
ADD COLUMN reserved_balance NUMERIC(19, 2) NOT NULL DEFAULT 0.00;

ALTER TABLE wallets
ADD CONSTRAINT reserved_balance_non_negative
CHECK (reserved_balance >= 0),
ADD CONSTRAINT reserved_balance_not_above_balance
CHECK (reserved_balance <= balance);

CREATE TABLE payment_reservations (
    payment_id UUID PRIMARY KEY REFERENCES payments(id),
    wallet_id UUID NOT NULL REFERENCES wallets(id),
    amount NUMERIC(19, 2) NOT NULL CHECK (amount > 0),
    currency VARCHAR(3) NOT NULL,
    status VARCHAR(20) NOT NULL
        CHECK (status IN ('ACTIVE', 'CAPTURED', 'RELEASED')),
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL
);

CREATE INDEX idx_payment_reservations_wallet_status
ON payment_reservations(wallet_id, status);

CREATE TABLE provider_attempts (
    payment_id UUID PRIMARY KEY REFERENCES payments(id),
    event_id UUID NOT NULL UNIQUE,
    idempotency_key UUID NOT NULL UNIQUE,
    status VARCHAR(20) NOT NULL
        CHECK (status IN ('IN_FLIGHT', 'COMPLETED')),
    lease_token UUID,
    lease_until TIMESTAMP,
    attempt_count INTEGER NOT NULL CHECK (attempt_count > 0),
    provider_payment_id VARCHAR(255),
    last_error VARCHAR(1000),
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL
);

CREATE INDEX idx_provider_attempts_expired_lease
ON provider_attempts(lease_until)
WHERE status = 'IN_FLIGHT';

ALTER TABLE ledger_transactions
ADD COLUMN payment_id UUID REFERENCES payments(id);

CREATE UNIQUE INDEX idx_ledger_transactions_payment_id
ON ledger_transactions(payment_id)
WHERE payment_id IS NOT NULL;

ALTER TABLE payments
ADD CONSTRAINT fk_payments_sender_wallet
    FOREIGN KEY (sender_wallet_id) REFERENCES wallets(id),
ADD CONSTRAINT fk_payments_receiver_wallet
    FOREIGN KEY (receiver_wallet_id) REFERENCES wallets(id),
ADD CONSTRAINT payment_amount_positive CHECK (amount > 0),
ADD CONSTRAINT payment_wallets_different CHECK (sender_wallet_id <> receiver_wallet_id);
