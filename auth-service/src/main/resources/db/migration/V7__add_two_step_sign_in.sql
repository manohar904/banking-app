-- Two-step sign-in: after the password, a 6-digit code sent by email must be entered too. Each person
-- turns it on themselves, after a code proves their email arrives.
ALTER TABLE users ADD COLUMN two_step_enabled BOOLEAN NOT NULL DEFAULT false;

-- Codes waiting to be entered. Only a hash is stored. One per person and purpose: asking again replaces it.
CREATE TABLE sign_in_codes (
    -- Given to the gateway, which keeps it in the half-finished session; never shown to the browser.
    id         UUID        PRIMARY KEY,
    user_id    BIGINT      NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    -- SIGN_IN finishes a sign-in; TURN_ON proves the email works before two-step is turned on.
    purpose    VARCHAR(20) NOT NULL CHECK (purpose IN ('SIGN_IN', 'TURN_ON')),
    code_hash  CHAR(64)    NOT NULL,
    attempts   INTEGER     NOT NULL DEFAULT 0,
    expires_at TIMESTAMPTZ NOT NULL,
    UNIQUE (user_id, purpose)
);

CREATE INDEX idx_sign_in_codes_expires_at ON sign_in_codes (expires_at);
