-- What a customer is saving towards with a savings account, such as "Holiday" and 2,000.00, and
-- optionally by when. One goal per account; progress is the account's balance, so nothing here
-- changes as money moves. Removed with the account.
CREATE TABLE savings_goals (
    account_id    BIGINT         PRIMARY KEY REFERENCES accounts (id) ON DELETE CASCADE,
    name          VARCHAR(40)    NOT NULL CHECK (length(btrim(name)) > 0),
    target_amount NUMERIC(19, 2) NOT NULL CHECK (target_amount > 0),
    target_date   DATE,
    created_at    TIMESTAMPTZ    NOT NULL,
    updated_at    TIMESTAMPTZ    NOT NULL
);
