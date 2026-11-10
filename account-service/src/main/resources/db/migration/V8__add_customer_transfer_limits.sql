-- Customers may now send transfers themselves, up to a daily limit. Only the transfers they sent
-- count towards it; one sent by staff on their behalf does not.
ALTER TABLE account_transactions ADD COLUMN by_customer BOOLEAN NOT NULL DEFAULT FALSE;

-- Finds today's outgoing customer transfers without reading the whole ledger.
CREATE INDEX idx_account_transactions_customer_transfers ON account_transactions (account_id, created_at)
    WHERE type = 'TRANSFER_OUT' AND by_customer;
