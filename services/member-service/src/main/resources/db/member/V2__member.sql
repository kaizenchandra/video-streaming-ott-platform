CREATE TABLE account (
 id UUID PRIMARY KEY,
 issuer VARCHAR(512) NOT NULL,
 subject VARCHAR(255) NOT NULL,
 created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
 UNIQUE(issuer,subject)
);
CREATE TABLE profile (
 id UUID PRIMARY KEY,
 account_id UUID NOT NULL REFERENCES account(id),
 name VARCHAR(80) NOT NULL CHECK(length(trim(name)) > 0),
 kids BOOLEAN NOT NULL,
 maturity INTEGER NOT NULL CHECK(maturity BETWEEN 0 AND 18),
 locale VARCHAR(35) NOT NULL,
 version BIGINT NOT NULL CHECK(version > 0),
 created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
 CHECK(NOT kids OR maturity <= 12)
);
CREATE INDEX profile_owner_idx ON profile(account_id,created_at,id);
CREATE TABLE device (
 id UUID PRIMARY KEY,
 account_id UUID NOT NULL REFERENCES account(id),
 installation_id VARCHAR(128) NOT NULL,
 name VARCHAR(80) NOT NULL,
 codecs VARCHAR(40) NOT NULL,
 drm VARCHAR(40) NOT NULL,
 status VARCHAR(10) NOT NULL CHECK(status IN ('ACTIVE','REVOKED')),
 version BIGINT NOT NULL CHECK(version > 0),
 created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
 UNIQUE(account_id,installation_id)
);
CREATE INDEX device_owner_status_idx ON device(account_id,status);
