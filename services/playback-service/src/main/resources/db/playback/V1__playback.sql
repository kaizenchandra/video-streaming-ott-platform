CREATE TABLE account_quota (account_id UUID PRIMARY KEY);
CREATE TABLE playback_session (
 id UUID PRIMARY KEY,
 account_id UUID NOT NULL REFERENCES account_quota(account_id),
 profile_id UUID NOT NULL,
 device_id UUID NOT NULL,
 content_id UUID NOT NULL,
 country CHAR(2) NOT NULL,
 idempotency_key VARCHAR(128) NOT NULL,
 fingerprint CHAR(64) NOT NULL,
 state VARCHAR(16) NOT NULL CHECK(state IN ('PENDING','ACTIVE','ENDED')),
 expires_at TIMESTAMPTZ NOT NULL,
 grant_expires_at TIMESTAMPTZ,
 origin_prefix VARCHAR(512) NOT NULL,
 created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
 UNIQUE(account_id,idempotency_key),
 CHECK(grant_expires_at IS NULL OR grant_expires_at<=expires_at)
);
CREATE INDEX playback_active_idx ON playback_session(account_id,expires_at) WHERE state IN ('PENDING','ACTIVE');
CREATE TABLE grant_audit (
 id UUID PRIMARY KEY,
 session_id UUID NOT NULL REFERENCES playback_session(id),
 token_hash CHAR(64) NOT NULL,
 expires_at TIMESTAMPTZ NOT NULL,
 created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE TABLE outbox_event (
 event_id UUID PRIMARY KEY,
 aggregate_id UUID NOT NULL,
 aggregate_version BIGINT NOT NULL,
 topic VARCHAR(120) NOT NULL,
 payload JSONB NOT NULL,
 created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
 published_at TIMESTAMPTZ,
 claim_token UUID,
 claimed_until TIMESTAMPTZ,
 next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
 attempts INTEGER NOT NULL DEFAULT 0
);
CREATE INDEX playback_outbox_pending_idx ON outbox_event(next_attempt_at,created_at,event_id) WHERE published_at IS NULL;

