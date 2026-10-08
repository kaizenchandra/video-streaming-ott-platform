CREATE TABLE plan (
 id UUID PRIMARY KEY,
 name VARCHAR(80) NOT NULL,
 price_minor BIGINT NOT NULL CHECK(price_minor>0),
 currency CHAR(3) NOT NULL,
 duration_days INTEGER NOT NULL CHECK(duration_days BETWEEN 1 AND 3650),
 max_streams INTEGER NOT NULL CHECK(max_streams BETWEEN 1 AND 10),
 ad_supported BOOLEAN NOT NULL,
 offline_allowed BOOLEAN NOT NULL
);
CREATE TABLE commerce_account (
 account_id UUID PRIMARY KEY,
 version BIGINT NOT NULL DEFAULT 0
);
CREATE TABLE purchase_order (
 id UUID PRIMARY KEY,
 account_id UUID NOT NULL REFERENCES commerce_account(account_id),
 plan_id UUID NOT NULL REFERENCES plan(id),
 price_minor BIGINT NOT NULL,
 currency CHAR(3) NOT NULL,
 duration_days INTEGER NOT NULL,
 max_streams INTEGER NOT NULL,
 ad_supported BOOLEAN NOT NULL,
 offline_allowed BOOLEAN NOT NULL,
 idempotency_key VARCHAR(128) NOT NULL,
 fingerprint CHAR(64) NOT NULL,
 state VARCHAR(32) NOT NULL CHECK(state IN ('CREATED','PENDING','PENDING_RECONCILIATION','PAID','FAILED')),
 provider_ref VARCHAR(200),
 created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
 UNIQUE(account_id,idempotency_key),
 UNIQUE(provider_ref)
);
CREATE UNIQUE INDEX one_open_purchase_idx ON purchase_order(account_id) WHERE state IN ('CREATED','PENDING','PENDING_RECONCILIATION');
CREATE INDEX order_owner_idx ON purchase_order(account_id,created_at,id);
CREATE TABLE subscription (
 account_id UUID PRIMARY KEY REFERENCES commerce_account(account_id),
 plan_id UUID NOT NULL,
 expires_at TIMESTAMPTZ NOT NULL,
 state VARCHAR(16) NOT NULL DEFAULT 'ACTIVE' CHECK(state IN ('ACTIVE','CANCELLED')),
 max_streams INTEGER NOT NULL,
 ad_supported BOOLEAN NOT NULL,
 offline_allowed BOOLEAN NOT NULL,
 version BIGINT NOT NULL
);
CREATE TABLE provider_inbox (
 event_id VARCHAR(128) PRIMARY KEY,
 body_hash CHAR(64) NOT NULL,
 received_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE TABLE invoice (
 order_id UUID PRIMARY KEY REFERENCES purchase_order(id),
 amount_minor BIGINT NOT NULL,
 currency CHAR(3) NOT NULL,
 paid_at TIMESTAMPTZ NOT NULL
);
CREATE TABLE payment_audit (
 id UUID PRIMARY KEY,
 order_id UUID NOT NULL REFERENCES purchase_order(id),
 action VARCHAR(40) NOT NULL,
 at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
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
CREATE INDEX commerce_outbox_pending_idx ON outbox_event(next_attempt_at,created_at,event_id) WHERE published_at IS NULL;
