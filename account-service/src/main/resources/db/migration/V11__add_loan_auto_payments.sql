-- Monthly loan payments are now taken automatically on their due date. When the account hasn't enough
-- money, the payment is tried again later, and this records which due date the customer was already told
-- about, so they get one message per missed payment rather than one per try.
ALTER TABLE loans ADD COLUMN auto_pay_failed_for DATE;

-- Finds the loans with a payment due without reading them all.
CREATE INDEX idx_loans_payment_due ON loans (next_payment_due) WHERE status = 'ACTIVE';
