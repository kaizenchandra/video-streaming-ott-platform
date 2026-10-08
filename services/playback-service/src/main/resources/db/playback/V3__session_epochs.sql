ALTER TABLE account_quota ADD COLUMN next_epoch BIGINT NOT NULL DEFAULT 0 CHECK(next_epoch>=0);
ALTER TABLE playback_session ADD COLUMN epoch BIGINT;
UPDATE playback_session SET epoch=(EXTRACT(EPOCH FROM created_at)*1000)::BIGINT;
ALTER TABLE playback_session ALTER COLUMN epoch SET NOT NULL;
ALTER TABLE playback_session ADD CONSTRAINT session_epoch_positive CHECK(epoch>0);
UPDATE account_quota q SET next_epoch=x.epoch FROM (SELECT account_id,max(epoch) AS epoch FROM playback_session GROUP BY account_id) x WHERE q.account_id=x.account_id;

CREATE FUNCTION allocate_account_epoch() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
 UPDATE account_quota SET next_epoch=GREATEST(next_epoch+1,(EXTRACT(EPOCH FROM clock_timestamp())*1000)::BIGINT)
 WHERE account_id=NEW.account_id RETURNING next_epoch INTO NEW.epoch;
 IF NOT FOUND THEN RAISE EXCEPTION 'Missing account quota'; END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER allocate_session_epoch BEFORE INSERT ON playback_session FOR EACH ROW EXECUTE FUNCTION allocate_account_epoch();
