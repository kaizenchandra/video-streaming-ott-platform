CREATE TABLE profile_owner(profile_id UUID PRIMARY KEY,account_id UUID NOT NULL);
CREATE TABLE watch_progress (
 id UUID NOT NULL UNIQUE DEFAULT gen_random_uuid(),profile_id UUID NOT NULL REFERENCES profile_owner(profile_id),content_id UUID NOT NULL,session_id UUID NOT NULL,epoch BIGINT NOT NULL CHECK(epoch>0),
 sequence BIGINT NOT NULL CHECK(sequence>0),position_millis BIGINT NOT NULL CHECK(position_millis>=0),duration_millis BIGINT NOT NULL CHECK(duration_millis BETWEEN 1 AND 86400000),
 version BIGINT NOT NULL CHECK(version>0),updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,PRIMARY KEY(profile_id,content_id),CHECK(position_millis<=duration_millis)
);
CREATE INDEX progress_recent_idx ON watch_progress(profile_id,updated_at DESC,content_id DESC);
CREATE TABLE progress_inbox(event_id UUID PRIMARY KEY,account_id UUID NOT NULL,fingerprint CHAR(64) NOT NULL,accepted BOOLEAN NOT NULL,received_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP);
CREATE TABLE session_sequence(session_id UUID PRIMARY KEY,profile_id UUID NOT NULL,content_id UUID NOT NULL,sequence BIGINT NOT NULL, fingerprint CHAR(64) NOT NULL);
CREATE TABLE watch_history(session_id UUID PRIMARY KEY,profile_id UUID NOT NULL REFERENCES profile_owner(profile_id),content_id UUID NOT NULL,epoch BIGINT NOT NULL,started_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,last_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP);
CREATE INDEX history_recent_idx ON watch_history(profile_id,last_at DESC,session_id DESC);
CREATE TABLE watchlist(profile_id UUID NOT NULL REFERENCES profile_owner(profile_id),content_id UUID NOT NULL,created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,PRIMARY KEY(profile_id,content_id));
CREATE INDEX watchlist_recent_idx ON watchlist(profile_id,created_at DESC,content_id DESC);
CREATE TABLE reaction(id UUID NOT NULL UNIQUE DEFAULT gen_random_uuid(),profile_id UUID NOT NULL REFERENCES profile_owner(profile_id),content_id UUID NOT NULL,rating INTEGER CHECK(rating BETWEEN 1 AND 5),reaction VARCHAR(12) CHECK(reaction IN ('LIKE','DISLIKE')),version BIGINT NOT NULL DEFAULT 1,updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,PRIMARY KEY(profile_id,content_id));
CREATE TABLE outbox_event(event_id UUID PRIMARY KEY,aggregate_id UUID NOT NULL,aggregate_version BIGINT NOT NULL,topic VARCHAR(120) NOT NULL,payload JSONB NOT NULL,created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,published_at TIMESTAMPTZ,claim_token UUID,claimed_until TIMESTAMPTZ,next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,attempts INTEGER NOT NULL DEFAULT 0);
CREATE INDEX experience_outbox_pending_idx ON outbox_event(next_attempt_at,created_at,event_id) WHERE published_at IS NULL;
