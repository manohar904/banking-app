-- Credit cards. Staff issue a card with a spending limit; purchases add to what the customer owes, and
-- payments from one of their accounts reduce it. Only the last four digits of a card's number exist
-- here: the bank never needs the rest, so it is never stored.
CREATE TABLE cards (
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    -- Customers live in the customer service's database, so there is no foreign key.
    customer_id   BIGINT         NOT NULL,
    last_four     VARCHAR(4)     NOT NULL CHECK (last_four ~ '^[0-9]{4}$'),
    expires_on    DATE           NOT NULL,
    -- ACTIVE, FROZEN or CLOSED
    status        VARCHAR(20)    NOT NULL CHECK (status IN ('ACTIVE', 'FROZEN', 'CLOSED')),
    credit_limit  NUMERIC(19, 2) NOT NULL CHECK (credit_limit > 0),
    -- What the customer owes. Never more than the limit at the time of a purchase.
    owed          NUMERIC(19, 2) NOT NULL DEFAULT 0 CHECK (owed >= 0),
    created_at    TIMESTAMPTZ    NOT NULL,
    closed_at     TIMESTAMPTZ,
    version       BIGINT         NOT NULL DEFAULT 0,
    CHECK (status <> 'CLOSED' OR owed = 0)
);

CREATE INDEX idx_cards_customer_id ON cards (customer_id, id);

-- Everything that changed what is owed. Rows are only ever inserted.
CREATE TABLE card_transactions (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    card_id     BIGINT         NOT NULL REFERENCES cards (id),
    -- PURCHASE or PAYMENT
    type        VARCHAR(20)    NOT NULL CHECK (type IN ('PURCHASE', 'PAYMENT')),
    amount      NUMERIC(19, 2) NOT NULL CHECK (amount > 0),
    owed_after  NUMERIC(19, 2) NOT NULL CHECK (owed_after >= 0),
    -- Where a purchase was made.
    merchant    VARCHAR(100),
    -- The account a payment came from.
    account_id  BIGINT REFERENCES accounts (id),
    created_at  TIMESTAMPTZ    NOT NULL,
    CHECK ((type = 'PURCHASE') = (merchant IS NOT NULL)),
    CHECK ((type = 'PAYMENT') = (account_id IS NOT NULL))
);

CREATE INDEX idx_card_transactions_card_id ON card_transactions (card_id, id);

-- A payment leaves the account through its ledger like any other entry.
ALTER TABLE account_transactions DROP CONSTRAINT account_transactions_type_check;
ALTER TABLE account_transactions ADD CONSTRAINT account_transactions_type_check
    CHECK (type IN ('DEPOSIT', 'WITHDRAWAL', 'TRANSFER_OUT', 'TRANSFER_IN', 'LOAN_PAYOUT', 'LOAN_REPAYMENT',
                    'CARD_PAYMENT'));

ALTER TABLE account_transactions ADD COLUMN card_id BIGINT REFERENCES cards (id);
ALTER TABLE account_transactions ADD CONSTRAINT account_transactions_card_check
    CHECK ((type = 'CARD_PAYMENT') = (card_id IS NOT NULL));
