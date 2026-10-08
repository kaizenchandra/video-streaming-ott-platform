CREATE TABLE live_event(
 id UUID PRIMARY KEY,content_id UUID NOT NULL UNIQUE REFERENCES content_item(id),league VARCHAR(120) NOT NULL,
 home_team VARCHAR(120) NOT NULL,away_team VARCHAR(120) NOT NULL,reference_country CHAR(2) NOT NULL,
 starts_at TIMESTAMPTZ NOT NULL,ends_at TIMESTAMPTZ NOT NULL,CHECK(starts_at<ends_at),
 state VARCHAR(16) NOT NULL DEFAULT 'SCHEDULED' CHECK(state IN('SCHEDULED','READY','LIVE','ENDED','ARCHIVED','CANCELLED')),
 version BIGINT NOT NULL DEFAULT 1 CHECK(version>0),feed_epoch BIGINT NOT NULL DEFAULT 1 CHECK(feed_epoch>0),
 packager_subject VARCHAR(255) NOT NULL,dvr_seconds INTEGER NOT NULL CHECK(dvr_seconds BETWEEN 0 AND 7200),
 feed_sequence BIGINT NOT NULL DEFAULT 0,feed_fingerprint CHAR(64),healthy BOOLEAN NOT NULL DEFAULT false,sealed BOOLEAN NOT NULL DEFAULT false,
 first_segment_at TIMESTAMPTZ,last_segment_at TIMESTAMPTZ,health_received_at TIMESTAMPTZ,manifest_sha CHAR(64),
 commentaries JSONB NOT NULL DEFAULT '[]',subtitles JSONB NOT NULL DEFAULT '[]',ended_at TIMESTAMPTZ,
 replay_content_id UUID REFERENCES content_item(id)
);
CREATE INDEX live_schedule_idx ON live_event(starts_at,id);
CREATE TABLE live_command(event_id UUID NOT NULL REFERENCES live_event(id),idempotency_key VARCHAR(128) NOT NULL,fingerprint CHAR(64) NOT NULL,PRIMARY KEY(event_id,idempotency_key));
CREATE TABLE live_audit(id UUID PRIMARY KEY,event_id UUID NOT NULL REFERENCES live_event(id),actor_hash CHAR(64) NOT NULL,action VARCHAR(80) NOT NULL,result_version BIGINT NOT NULL,at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP);
CREATE TABLE live_highlight(id UUID PRIMARY KEY,event_id UUID NOT NULL REFERENCES live_event(id),content_id UUID NOT NULL REFERENCES content_item(id),label VARCHAR(160) NOT NULL,start_millis BIGINT NOT NULL CHECK(start_millis>=0),end_millis BIGINT NOT NULL CHECK(end_millis>start_millis),UNIQUE(event_id,content_id));
