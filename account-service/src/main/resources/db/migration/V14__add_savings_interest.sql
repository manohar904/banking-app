-- Savings accounts earn interest, worked out on each day's closing balance and paid on the first of the
-- month for the month before. This is the day each one's next payment is due; checking accounts have none.
ALTER TABLE accounts ADD COLUMN next_interest_on DATE;
-- Existing savings accounts get their first payment at the start of next month, for this month.
UPDATE accounts SET next_interest_on = (date_trunc('month', now()) + interval '1 month')::date
    WHERE type = 'SAVINGS';
ALTER TABLE accounts ADD CONSTRAINT accounts_interest_check CHECK (type = 'SAVINGS' OR next_interest_on IS NULL);

-- Finds the accounts with interest due without reading them all. New savings accounts start with none
-- and are given their first date by the interest job.
CREATE INDEX idx_accounts_interest_due ON accounts (next_interest_on) WHERE type = 'SAVINGS';

-- Interest is a new kind of ledger entry: money in, like a deposit.
ALTER TABLE account_transactions DROP CONSTRAINT account_transactions_type_check;
ALTER TABLE account_transactions ADD CONSTRAINT account_transactions_type_check
    CHECK (type IN ('DEPOSIT', 'WITHDRAWAL', 'TRANSFER_OUT', 'TRANSFER_IN', 'LOAN_PAYOUT', 'LOAN_REPAYMENT',
                    'CARD_PAYMENT', 'INTEREST'));
