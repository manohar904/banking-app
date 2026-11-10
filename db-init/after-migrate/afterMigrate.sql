-- Runs as the owner after every migration step, even when there was nothing to migrate, so it also
-- brings an older database up to date. ${app_role} is the login the running service uses.

-- Read and write rows, and nothing more: no creating, changing or dropping tables, and no TRUNCATE.
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO ${app_role};
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO ${app_role};

-- The service does not migrate, so it has no use for Flyway's record of what ran.
REVOKE ALL ON ${flyway:table} FROM ${app_role};

-- The audit log can only be added to and read. The trigger refuses changes too, but only the owner
-- could remove it, and the service is not the owner.
REVOKE UPDATE, DELETE ON audit_log FROM ${app_role};
