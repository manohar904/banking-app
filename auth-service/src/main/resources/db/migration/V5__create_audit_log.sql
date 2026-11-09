-- Who did what. Written in the same transaction as the change, so neither can exist without the other.
CREATE TABLE audit_log (
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    occurred_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    -- Empty when the action was not made for a person, such as one service calling another.
    actor_user_id BIGINT,
    actor_role    VARCHAR(20) NOT NULL,
    action        VARCHAR(50) NOT NULL,
    target_id     VARCHAR(64) NOT NULL,
    details       TEXT        NOT NULL
);

CREATE INDEX idx_audit_log_actor_user_id ON audit_log (actor_user_id, id);
CREATE INDEX idx_audit_log_target_id ON audit_log (target_id, id);

-- Entries can only be added. This stops mistakes and application bugs, not a database administrator.
CREATE FUNCTION refuse_audit_log_change() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'audit_log entries cannot be changed or removed';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER audit_log_no_row_changes BEFORE UPDATE OR DELETE ON audit_log
    FOR EACH ROW EXECUTE FUNCTION refuse_audit_log_change();
CREATE TRIGGER audit_log_no_truncate BEFORE TRUNCATE ON audit_log
    FOR EACH STATEMENT EXECUTE FUNCTION refuse_audit_log_change();
