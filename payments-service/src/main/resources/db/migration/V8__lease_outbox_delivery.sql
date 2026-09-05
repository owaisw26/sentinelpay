ALTER TABLE outbox_events
ADD COLUMN lease_token UUID,
ADD COLUMN lease_until TIMESTAMP,
ADD COLUMN attempt_count INTEGER NOT NULL DEFAULT 0,
ADD COLUMN next_attempt_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
ADD COLUMN last_error VARCHAR(1000),
ADD CONSTRAINT outbox_lease_is_complete
    CHECK (
        (lease_token IS NULL AND lease_until IS NULL)
        OR (lease_token IS NOT NULL AND lease_until IS NOT NULL)
    ),
ADD CONSTRAINT outbox_attempt_count_non_negative
    CHECK (attempt_count >= 0);

CREATE INDEX idx_outbox_events_pending_delivery
ON outbox_events(next_attempt_at, created_at, id)
WHERE published_at IS NULL;

