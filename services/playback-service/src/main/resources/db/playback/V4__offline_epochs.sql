ALTER TABLE offline_grant ADD COLUMN epoch BIGINT;
UPDATE offline_grant SET epoch=(EXTRACT(EPOCH FROM expires_at-interval '24 hours')*1000)::BIGINT;
ALTER TABLE offline_grant ALTER COLUMN epoch SET NOT NULL;
ALTER TABLE offline_grant ADD CONSTRAINT offline_epoch_positive CHECK(epoch>0);
UPDATE account_quota q SET next_epoch=GREATEST(q.next_epoch,x.epoch) FROM (SELECT account_id,max(epoch) AS epoch FROM offline_grant GROUP BY account_id) x WHERE q.account_id=x.account_id;

CREATE TRIGGER allocate_offline_epoch BEFORE INSERT ON offline_grant FOR EACH ROW EXECUTE FUNCTION allocate_account_epoch();
