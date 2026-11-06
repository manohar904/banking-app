-- Answers to requests sent with an Idempotency-Key, so that a repeat gets the first answer instead of
-- being carried out again. Written in the same transaction as the change; kept for 24 hours.
-- The response columns are filled in before that transaction commits.
CREATE TABLE idempotency_keys (
    caller                VARCHAR(100) NOT NULL,
    idem_key              VARCHAR(100) NOT NULL,
    fingerprint           CHAR(64)     NOT NULL,
    response_status       INTEGER,
    response_content_type VARCHAR(200),
    response_location     VARCHAR(500),
    response_body         BYTEA,
    created_at            TIMESTAMPTZ  NOT NULL DEFAULT now(),
    PRIMARY KEY (caller, idem_key)
);

CREATE INDEX idempotency_keys_created_at ON idempotency_keys (created_at);
