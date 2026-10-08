ALTER TABLE content_item ADD COLUMN genres JSONB NOT NULL DEFAULT '[]' CHECK(jsonb_typeof(genres)='array');
ALTER TABLE content_item ADD COLUMN actors JSONB NOT NULL DEFAULT '[]' CHECK(jsonb_typeof(actors)='array');
ALTER TABLE content_item ADD COLUMN release_date DATE;
