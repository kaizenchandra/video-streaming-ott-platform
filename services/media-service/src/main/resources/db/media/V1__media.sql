CREATE TABLE media_job (
 id UUID PRIMARY KEY,content_id UUID NOT NULL,asset_version BIGINT NOT NULL CHECK(asset_version>0),source_key VARCHAR(512) NOT NULL UNIQUE,
 expected_bytes BIGINT NOT NULL CHECK(expected_bytes BETWEEN 1 AND 268435456),source_checksum CHAR(64) NOT NULL,
 state VARCHAR(16) NOT NULL CHECK(state IN ('WAITING','UPLOADED','PROCESSING','READY','FAILED')),
 origin_prefix VARCHAR(512) NOT NULL,manifest_checksum CHAR(64),failure_code VARCHAR(80),created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
 updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,UNIQUE(content_id,asset_version)
);
CREATE INDEX media_dispatch_idx ON media_job(created_at) WHERE state='UPLOADED';
CREATE TABLE outbox_event(event_id UUID PRIMARY KEY,aggregate_id UUID NOT NULL,aggregate_version BIGINT NOT NULL,topic VARCHAR(120) NOT NULL,payload JSONB NOT NULL,created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,published_at TIMESTAMPTZ,claim_token UUID,claimed_until TIMESTAMPTZ,next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,attempts INTEGER NOT NULL DEFAULT 0);
CREATE INDEX media_outbox_pending_idx ON outbox_event(next_attempt_at,created_at,event_id) WHERE published_at IS NULL;
