ALTER TABLE payments
ADD COLUMN provider_payment_id VARCHAR(255);

CREATE UNIQUE INDEX idx_payments_provider_payment_id
ON payments(provider_payment_id)
WHERE provider_payment_id IS NOT NULL;
