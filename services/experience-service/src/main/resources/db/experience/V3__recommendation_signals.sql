CREATE TABLE personalization(
 profile_id UUID PRIMARY KEY REFERENCES profile_owner(profile_id),enabled BOOLEAN NOT NULL DEFAULT false,
 enabled_since TIMESTAMPTZ,version BIGINT NOT NULL DEFAULT 1 CHECK(version>0),
 CHECK(enabled=(enabled_since IS NOT NULL))
);
CREATE TABLE recommendation_inbox(event_id UUID PRIMARY KEY,fingerprint CHAR(64) NOT NULL,received_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP);
CREATE TABLE daily_view(
 profile_id UUID NOT NULL REFERENCES personalization(profile_id),content_id UUID NOT NULL,day DATE NOT NULL,
 last_at TIMESTAMPTZ NOT NULL,PRIMARY KEY(profile_id,content_id,day)
);
CREATE INDEX daily_view_window_idx ON daily_view(last_at,content_id);
CREATE INDEX daily_profile_quota_idx ON daily_view(profile_id,day,content_id);
CREATE INDEX catalog_genres_idx ON search_document USING GIN((snapshot->'genres') jsonb_path_ops);
CREATE INDEX catalog_actors_idx ON search_document USING GIN((snapshot->'actors') jsonb_path_ops);
