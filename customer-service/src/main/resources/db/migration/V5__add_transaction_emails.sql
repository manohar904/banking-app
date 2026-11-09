-- Every customer gets transaction emails unless staff turn them off.
ALTER TABLE customers ADD COLUMN transaction_emails_enabled BOOLEAN NOT NULL DEFAULT TRUE;

-- Emails waiting to go out. event_id stops a redelivered event from sending a second email, so rows
-- are kept for as long as Kafka keeps events (7 days) even after they are sent.
CREATE TABLE transaction_emails (
    event_id        VARCHAR(64)  PRIMARY KEY,
    recipient       VARCHAR(254) NOT NULL,
    subject         TEXT         NOT NULL,
    body            TEXT         NOT NULL,
    attempts        INTEGER      NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    sent_at         TIMESTAMPTZ,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX idx_transaction_emails_unsent ON transaction_emails (next_attempt_at) WHERE sent_at IS NULL;
CREATE INDEX idx_transaction_emails_created_at ON transaction_emails (created_at);
