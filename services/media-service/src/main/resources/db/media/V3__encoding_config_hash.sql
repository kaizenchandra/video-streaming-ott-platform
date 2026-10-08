ALTER TABLE media_job ADD COLUMN encoding_config_hash CHAR(64) NOT NULL DEFAULT '56845cc01857ebcb2cd91c2c662eb6e03a7af812068272df8aa85c6a9553bf38';
ALTER TABLE media_job ADD CONSTRAINT encoding_config_hash_format CHECK(encoding_config_hash ~ '^[a-f0-9]{64}$');
