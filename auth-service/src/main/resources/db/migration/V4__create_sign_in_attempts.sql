-- Sign-in attempts per email in the current window. See SignInLimiter.
-- A hash, because people mistype emails and this table also holds addresses nobody has an account for.
CREATE TABLE sign_in_attempts (
    email_hash        CHAR(64)    PRIMARY KEY,
    attempts          INTEGER     NOT NULL,
    window_started_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX sign_in_attempts_window_started_at ON sign_in_attempts (window_started_at);
