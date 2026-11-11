-- Questions customers send through Help, and the conversation with the bank about each one.
-- Deleting a customer deletes their requests too.
CREATE TABLE help_requests (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    customer_id BIGINT       NOT NULL REFERENCES customers (id) ON DELETE CASCADE,
    subject     VARCHAR(120) NOT NULL,
    -- WAITING_FOR_BANK, ANSWERED or CLOSED
    status      VARCHAR(20)  NOT NULL,
    created_at  TIMESTAMPTZ  NOT NULL,
    updated_at  TIMESTAMPTZ  NOT NULL
);

CREATE INDEX idx_help_requests_customer ON help_requests (customer_id, updated_at DESC);
CREATE INDEX idx_help_requests_status ON help_requests (status, updated_at DESC);

CREATE TABLE help_messages (
    id             BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    request_id     BIGINT      NOT NULL REFERENCES help_requests (id) ON DELETE CASCADE,
    -- True when staff wrote it. Customers see it as from "the bank", not from a named person.
    from_bank      BOOLEAN     NOT NULL,
    author_user_id BIGINT,
    body           TEXT        NOT NULL CHECK (char_length(body) <= 2000),
    created_at     TIMESTAMPTZ NOT NULL
);

CREATE INDEX idx_help_messages_request ON help_messages (request_id, id);
