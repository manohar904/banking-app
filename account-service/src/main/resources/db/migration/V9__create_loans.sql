-- Loans. A customer asks for one, paid into and repaid from one of their accounts; staff approve or
-- reject it. Approving pays the money in, and each monthly repayment takes a fixed amount back.
CREATE TABLE loans (
    id                  BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    customer_id         BIGINT         NOT NULL,
    -- Deleting the account deletes requests made with it. An account that a loan has paid into has
    -- ledger entries, so it can't be deleted anyway, and the service refuses while one is waiting.
    account_id          BIGINT         NOT NULL REFERENCES accounts (id) ON DELETE CASCADE,
    -- REQUESTED, REJECTED, CANCELLED, ACTIVE or PAID_OFF
    status              VARCHAR(20)    NOT NULL,
    principal           NUMERIC(19, 2) NOT NULL CHECK (principal > 0),
    -- Percent a year, fixed for the life of the loan.
    annual_rate         NUMERIC(5, 2)  NOT NULL CHECK (annual_rate >= 0),
    term_months         INTEGER        NOT NULL CHECK (term_months > 0),
    monthly_payment     NUMERIC(19, 2) NOT NULL CHECK (monthly_payment > 0),
    remaining_principal NUMERIC(19, 2) NOT NULL CHECK (remaining_principal >= 0),
    payments_made       INTEGER        NOT NULL DEFAULT 0 CHECK (payments_made >= 0),
    purpose             VARCHAR(200)   NOT NULL,
    -- Why staff rejected it, shown to the customer.
    decision_note       VARCHAR(300),
    next_payment_due    DATE,
    requested_at        TIMESTAMPTZ    NOT NULL,
    decided_at          TIMESTAMPTZ,
    closed_at           TIMESTAMPTZ,
    version             BIGINT         NOT NULL DEFAULT 0,
    CHECK (status IN ('REQUESTED', 'REJECTED', 'CANCELLED', 'ACTIVE', 'PAID_OFF')),
    CHECK ((status = 'ACTIVE') = (next_payment_due IS NOT NULL))
);

CREATE INDEX idx_loans_customer_id ON loans (customer_id, id DESC);
CREATE INDEX idx_loans_status ON loans (status, id DESC);
CREATE INDEX idx_loans_account_id ON loans (account_id);

-- Each repayment, split into interest and the part that paid the loan down.
CREATE TABLE loan_repayments (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    loan_id         BIGINT         NOT NULL REFERENCES loans (id),
    amount          NUMERIC(19, 2) NOT NULL CHECK (amount > 0),
    interest        NUMERIC(19, 2) NOT NULL CHECK (interest >= 0),
    principal       NUMERIC(19, 2) NOT NULL CHECK (principal >= 0),
    remaining_after NUMERIC(19, 2) NOT NULL CHECK (remaining_after >= 0),
    created_at      TIMESTAMPTZ    NOT NULL,
    CHECK (amount = interest + principal)
);

CREATE INDEX idx_loan_repayments_loan_id ON loan_repayments (loan_id, id);

-- The money itself moves through the account's ledger like any other entry.
ALTER TABLE account_transactions DROP CONSTRAINT account_transactions_type_check;
ALTER TABLE account_transactions ADD CONSTRAINT account_transactions_type_check
    CHECK (type IN ('DEPOSIT', 'WITHDRAWAL', 'TRANSFER_OUT', 'TRANSFER_IN', 'LOAN_PAYOUT', 'LOAN_REPAYMENT'));

ALTER TABLE account_transactions ADD COLUMN loan_id BIGINT REFERENCES loans (id);
ALTER TABLE account_transactions ADD CONSTRAINT account_transactions_loan_check
    CHECK ((type IN ('LOAN_PAYOUT', 'LOAN_REPAYMENT')) = (loan_id IS NOT NULL));
