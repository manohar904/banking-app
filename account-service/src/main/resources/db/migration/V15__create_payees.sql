-- People and accounts a customer pays often, saved under a name such as "Landlord", so they can be
-- picked instead of typing a 12-digit account number each time.
CREATE TABLE payees (
    id             BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    customer_id    BIGINT       NOT NULL,
    name           VARCHAR(40)  NOT NULL CHECK (length(btrim(name)) > 0),
    account_number VARCHAR(12)  NOT NULL CHECK (account_number ~ '^[0-9]{12}$'),
    created_at     TIMESTAMPTZ  NOT NULL
);

-- One name and one entry per account for each customer.
CREATE UNIQUE INDEX uq_payees_name ON payees (customer_id, lower(name));
CREATE UNIQUE INDEX uq_payees_account ON payees (customer_id, account_number);
