CREATE TABLE search_inbox(event_id UUID PRIMARY KEY,fingerprint CHAR(64) NOT NULL,received_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP);
CREATE TABLE search_document(
 content_id UUID PRIMARY KEY,version BIGINT NOT NULL CHECK(version>0),snapshot JSONB NOT NULL,
 indexed_version BIGINT NOT NULL DEFAULT 0 CHECK(indexed_version>=0),updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
 claim_token UUID,claimed_until TIMESTAMPTZ,next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,attempts INTEGER NOT NULL DEFAULT 0
);
CREATE INDEX search_index_pending_idx ON search_document(next_attempt_at,content_id) WHERE indexed_version<version;
CREATE TABLE search_rebuild(id UUID PRIMARY KEY,after_id UUID,state VARCHAR(16) NOT NULL CHECK(state IN('RUNNING','COMPLETE')),created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,claim_token UUID,claimed_until TIMESTAMPTZ);
CREATE UNIQUE INDEX search_single_rebuild_idx ON search_rebuild(state) WHERE state='RUNNING';
