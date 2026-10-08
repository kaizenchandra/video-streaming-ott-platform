CREATE TABLE content_item (
 id UUID PRIMARY KEY,
 type VARCHAR(16) NOT NULL CHECK(type IN ('MOVIE','SERIES','SEASON','EPISODE','SPORT')),
 parent_id UUID REFERENCES content_item(id),
 maturity INTEGER NOT NULL CHECK(maturity BETWEEN 0 AND 18),
 policy_version BIGINT NOT NULL DEFAULT 0 CHECK(policy_version>=0),
 state VARCHAR(16) NOT NULL CHECK(state IN ('DRAFT','REVIEW','PUBLISHED')),
 version BIGINT NOT NULL CHECK(version>0),
 created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
 CHECK((type IN ('SEASON','EPISODE') AND parent_id IS NOT NULL) OR (type NOT IN ('SEASON','EPISODE') AND parent_id IS NULL))
);
CREATE INDEX content_parent_idx ON content_item(parent_id);
CREATE INDEX content_browse_idx ON content_item(state,created_at,id);
CREATE TABLE localization (
 content_id UUID NOT NULL REFERENCES content_item(id),
 locale VARCHAR(35) NOT NULL,
 title VARCHAR(200) NOT NULL,
 synopsis VARCHAR(4000) NOT NULL,
 PRIMARY KEY(content_id,locale)
);
CREATE TABLE rights_grant (
 id UUID PRIMARY KEY,
 content_id UUID NOT NULL REFERENCES content_item(id),
 country CHAR(2) NOT NULL,
 start_at TIMESTAMPTZ NOT NULL,
 end_at TIMESTAMPTZ NOT NULL,
 offline_allowed BOOLEAN NOT NULL,
 revoked BOOLEAN NOT NULL DEFAULT false,
 version BIGINT NOT NULL CHECK(version>0),
 CHECK(start_at<end_at)
);
CREATE INDEX rights_evaluate_idx ON rights_grant(content_id,country,start_at,end_at);
CREATE TABLE ready_asset (
 content_id UUID NOT NULL REFERENCES content_item(id),
 asset_version BIGINT NOT NULL,
 origin_prefix VARCHAR(512) NOT NULL,
 checksum VARCHAR(128) NOT NULL,
 PRIMARY KEY(content_id,asset_version)
);
CREATE TABLE published_version (
 content_id UUID NOT NULL REFERENCES content_item(id),
 version BIGINT NOT NULL,
 asset_version BIGINT NOT NULL,
 metadata JSONB NOT NULL,
 published_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
 PRIMARY KEY(content_id,version),
 FOREIGN KEY(content_id,asset_version) REFERENCES ready_asset(content_id,asset_version)
);
CREATE TABLE inbox (event_id UUID PRIMARY KEY, received_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP);
CREATE TABLE outbox_event (
 event_id UUID PRIMARY KEY,
 aggregate_id UUID NOT NULL,
 aggregate_version BIGINT NOT NULL,
 topic VARCHAR(120) NOT NULL,
 payload JSONB NOT NULL,
 created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
 published_at TIMESTAMPTZ
);
CREATE INDEX content_outbox_pending_idx ON outbox_event(created_at,event_id) WHERE published_at IS NULL;
