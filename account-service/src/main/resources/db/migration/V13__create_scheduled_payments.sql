-- Transfers a customer sets up once and the bank sends every month on the same day, like rent.
CREATE TABLE scheduled_payments (
    id                BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    customer_id       BIGINT         NOT NULL,
    -- Removed with the account. An account with an active one can't be deleted, so only cancelled
    -- ones ever go this way.
    from_account_id   BIGINT         NOT NULL REFERENCES accounts (id) ON DELETE CASCADE,
    to_account_number VARCHAR(12)    NOT NULL,
    amount            NUMERIC(19, 2) NOT NULL CHECK (amount > 0),
    -- 28 at most, so every month has the day.
    day_of_month      INTEGER        NOT NULL CHECK (day_of_month BETWEEN 1 AND 28),
    reference         VARCHAR(60),
    status            VARCHAR(20)    NOT NULL CHECK (status IN ('ACTIVE', 'CANCELLED')),
    next_payment_on   DATE           NOT NULL,
    last_sent_on      DATE,
    -- Why the last try failed, and for which payment day, so the customer is told once per payment.
    problem           VARCHAR(200),
    problem_for       DATE,
    created_at        TIMESTAMPTZ    NOT NULL,
    cancelled_at      TIMESTAMPTZ
);

CREATE INDEX idx_scheduled_payments_customer ON scheduled_payments (customer_id, id);
CREATE INDEX idx_scheduled_payments_from_account ON scheduled_payments (from_account_id);
-- Finds the payments due without reading them all.
CREATE INDEX idx_scheduled_payments_due ON scheduled_payments (next_payment_on) WHERE status = 'ACTIVE';
