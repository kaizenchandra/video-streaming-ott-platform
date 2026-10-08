CREATE FUNCTION protect_subscription_audit() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'Audit records are append only'; END; $$;
CREATE TRIGGER subscription_audit_append_only BEFORE UPDATE OR DELETE ON subscription_audit FOR EACH ROW EXECUTE FUNCTION protect_subscription_audit();
