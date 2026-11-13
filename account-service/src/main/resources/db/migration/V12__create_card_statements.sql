-- Monthly card statements. On the first of each month every open card gets a statement of what is owed,
-- with a minimum payment and a date it is due by. When a statement isn't paid in full by the next one,
-- interest is added on the part still unpaid; purchases made since stay free of interest until then.
CREATE TABLE card_statements (
    id               BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    card_id          BIGINT         NOT NULL REFERENCES cards (id),
    -- The days the statement covers, both included.
    period_start     DATE           NOT NULL,
    period_end       DATE           NOT NULL,
    -- Interest added when this statement was made, already part of balance.
    interest         NUMERIC(19, 2) NOT NULL CHECK (interest >= 0),
    -- What was owed when the statement was made.
    balance          NUMERIC(19, 2) NOT NULL CHECK (balance >= 0),
    minimum_payment  NUMERIC(19, 2) NOT NULL CHECK (minimum_payment >= 0 AND minimum_payment <= balance),
    due_on           DATE           NOT NULL,
    created_at       TIMESTAMPTZ    NOT NULL,
    CHECK (period_end >= period_start),
    UNIQUE (card_id, period_end)
);

CREATE INDEX idx_card_statements_card_id ON card_statements (card_id, id);

-- When each card's next statement is due. Existing cards get their first one next month.
ALTER TABLE cards ADD COLUMN next_statement_on DATE;
UPDATE cards SET next_statement_on = (date_trunc('month', created_at) + interval '1 month')::date;
ALTER TABLE cards ALTER COLUMN next_statement_on SET NOT NULL;

-- Finds the cards with a statement due without reading them all.
CREATE INDEX idx_cards_statement_due ON cards (next_statement_on) WHERE status <> 'CLOSED';

-- Interest is a third kind of card entry: it adds to what is owed, like a purchase, but has no shop.
ALTER TABLE card_transactions DROP CONSTRAINT card_transactions_type_check;
ALTER TABLE card_transactions ADD CONSTRAINT card_transactions_type_check
    CHECK (type IN ('PURCHASE', 'PAYMENT', 'INTEREST'));
