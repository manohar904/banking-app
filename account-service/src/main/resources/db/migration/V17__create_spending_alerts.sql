-- An amount a customer wants to be emailed about: when money out of the account in one calendar month
-- (bank time zone) goes over it. One per account. It never stops a payment. alerted_for is the first
-- day of the month the email was last sent for, so each month gets at most one email.
CREATE TABLE spending_alerts (
    account_id  BIGINT         PRIMARY KEY REFERENCES accounts (id) ON DELETE CASCADE,
    amount      NUMERIC(19, 2) NOT NULL CHECK (amount > 0),
    alerted_for DATE,
    created_at  TIMESTAMPTZ    NOT NULL,
    updated_at  TIMESTAMPTZ    NOT NULL
);
