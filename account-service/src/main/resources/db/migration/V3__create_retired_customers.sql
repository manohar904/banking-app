-- Customers who may not get new accounts because they are being, or have been, deleted.
-- Customer ids are never reused, so rows stay here for good.
CREATE TABLE retired_customers (
    customer_id BIGINT      PRIMARY KEY,
    retired_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
