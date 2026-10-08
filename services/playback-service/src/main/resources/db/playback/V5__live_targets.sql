ALTER TABLE playback_session ADD COLUMN target_kind VARCHAR(16) NOT NULL DEFAULT 'VOD' CHECK(target_kind IN('VOD','LIVE','DVR','REPLAY'));
ALTER TABLE playback_session ADD COLUMN target_id UUID;
UPDATE playback_session SET target_id=content_id;
CREATE FUNCTION default_playback_target() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
 IF NEW.target_id IS NULL THEN NEW.target_id=NEW.content_id; END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER default_session_target BEFORE INSERT ON playback_session FOR EACH ROW EXECUTE FUNCTION default_playback_target();
ALTER TABLE playback_session ALTER COLUMN target_id SET NOT NULL;
