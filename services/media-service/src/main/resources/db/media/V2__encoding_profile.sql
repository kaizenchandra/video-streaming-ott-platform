ALTER TABLE media_job ADD COLUMN encoding_profile VARCHAR(20) NOT NULL DEFAULT 'VOD' CHECK(encoding_profile IN ('VOD','SPORT'));
