CREATE FUNCTION protect_member_security_audit() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'Audit records are append only'; END; $$;
CREATE TRIGGER member_audit_append_only BEFORE UPDATE OR DELETE ON member_security_audit FOR EACH ROW EXECUTE FUNCTION protect_member_security_audit();
