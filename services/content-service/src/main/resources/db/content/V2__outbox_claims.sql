ALTER TABLE outbox_event ADD COLUMN claim_token UUID;
ALTER TABLE outbox_event ADD COLUMN claimed_until TIMESTAMPTZ;
ALTER TABLE outbox_event ADD COLUMN next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP;
ALTER TABLE outbox_event ADD COLUMN attempts INTEGER NOT NULL DEFAULT 0;
CREATE INDEX content_outbox_retry_idx ON outbox_event(next_attempt_at) WHERE published_at IS NULL;
