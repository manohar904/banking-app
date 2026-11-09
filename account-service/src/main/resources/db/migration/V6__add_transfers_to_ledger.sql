-- A transfer writes one entry on each account. transfer_id links the two.
ALTER TABLE account_transactions DROP CONSTRAINT account_transactions_type_check;
ALTER TABLE account_transactions ADD CONSTRAINT account_transactions_type_check
    CHECK (type IN ('DEPOSIT', 'WITHDRAWAL', 'TRANSFER_OUT', 'TRANSFER_IN'));

ALTER TABLE account_transactions ADD COLUMN transfer_id UUID;
ALTER TABLE account_transactions ADD COLUMN counterparty_account_number VARCHAR(12);

ALTER TABLE account_transactions ADD CONSTRAINT account_transactions_transfer_details_check CHECK (
    (type IN ('TRANSFER_OUT', 'TRANSFER_IN')) = (transfer_id IS NOT NULL)
    AND (transfer_id IS NULL) = (counterparty_account_number IS NULL));

-- At most one entry per side, so a transfer cannot be recorded twice.
CREATE UNIQUE INDEX uq_account_transactions_transfer_side ON account_transactions (transfer_id, type)
    WHERE transfer_id IS NOT NULL;
