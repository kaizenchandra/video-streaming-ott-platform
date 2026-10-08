CREATE TABLE offline_grant (
 id UUID PRIMARY KEY,account_id UUID NOT NULL REFERENCES account_quota(account_id),profile_id UUID NOT NULL,device_id UUID NOT NULL,content_id UUID NOT NULL,
 country CHAR(2) NOT NULL,idempotency_key VARCHAR(128) NOT NULL,fingerprint CHAR(64) NOT NULL,origin_prefix VARCHAR(512) NOT NULL,
 expires_at TIMESTAMPTZ NOT NULL,revoked BOOLEAN NOT NULL DEFAULT FALSE,UNIQUE(account_id,idempotency_key)
);
CREATE INDEX offline_active_idx ON offline_grant(account_id,expires_at) WHERE NOT revoked;
CREATE TABLE offline_audit (id UUID PRIMARY KEY,grant_id UUID NOT NULL REFERENCES offline_grant(id),token_hash CHAR(64) NOT NULL,expires_at TIMESTAMPTZ NOT NULL,created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP);
