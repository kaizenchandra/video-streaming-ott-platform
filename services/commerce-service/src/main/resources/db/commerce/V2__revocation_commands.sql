ALTER TABLE commerce_account ADD COLUMN access_revoked BOOLEAN NOT NULL DEFAULT false;
CREATE TABLE commerce_security_command(id UUID PRIMARY KEY,account_id UUID NOT NULL REFERENCES commerce_account(account_id),fingerprint CHAR(64) NOT NULL,active BOOLEAN NOT NULL,version BIGINT NOT NULL,at TIMESTAMPTZ NOT NULL);
CREATE TABLE subscription_audit(id UUID PRIMARY KEY,account_id UUID NOT NULL REFERENCES commerce_account(account_id),actor_hash CHAR(64) NOT NULL,action VARCHAR(60) NOT NULL,version BIGINT NOT NULL,at TIMESTAMPTZ NOT NULL);
