-- Links sent to people who forgot their password. Only a hash is stored, so reading this table gives
-- nobody a working link. One per user: asking again replaces the earlier link.
CREATE TABLE password_reset_tokens (
    token_hash CHAR(64)    PRIMARY KEY,
    user_id    BIGINT      NOT NULL UNIQUE REFERENCES users (id) ON DELETE CASCADE,
    expires_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX idx_password_reset_tokens_expires_at ON password_reset_tokens (expires_at);
