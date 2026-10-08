ALTER TABLE outbox_event ADD COLUMN claim_token UUID;
ALTER TABLE outbox_event ADD COLUMN claimed_until TIMESTAMPTZ;
ALTER TABLE outbox_event ADD COLUMN next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP;
CREATE INDEX outbox_retry_idx ON outbox_event(next_attempt_at) WHERE published_at IS NULL;
