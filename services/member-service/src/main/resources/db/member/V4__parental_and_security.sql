ALTER TABLE account ADD COLUMN status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE' CHECK(status IN('ACTIVE','SUSPENDED'));
ALTER TABLE account ADD COLUMN security_epoch BIGINT NOT NULL DEFAULT 1;
CREATE TABLE parental_control(account_id UUID PRIMARY KEY REFERENCES account(id),pin_hash VARCHAR(100),revision BIGINT NOT NULL DEFAULT 0,failures INTEGER NOT NULL DEFAULT 0 CHECK(failures BETWEEN 0 AND 5),blocked_until TIMESTAMPTZ);
CREATE TABLE profile_selection(account_id UUID NOT NULL REFERENCES account(id),profile_id UUID NOT NULL REFERENCES profile(id),device_id UUID NOT NULL REFERENCES device(id),token_hash CHAR(64) NOT NULL,expires_at TIMESTAMPTZ NOT NULL,pin_revision BIGINT NOT NULL,profile_version BIGINT NOT NULL,PRIMARY KEY(account_id,profile_id,device_id),UNIQUE(token_hash));
CREATE TABLE member_command(id UUID PRIMARY KEY,account_id UUID NOT NULL REFERENCES account(id),fingerprint CHAR(64) NOT NULL,status VARCHAR(16) NOT NULL,security_epoch BIGINT NOT NULL,at TIMESTAMPTZ NOT NULL);
CREATE TABLE member_security_audit(id UUID PRIMARY KEY,account_id UUID NOT NULL REFERENCES account(id),actor_hash CHAR(64) NOT NULL,action VARCHAR(40) NOT NULL,at TIMESTAMPTZ NOT NULL);
