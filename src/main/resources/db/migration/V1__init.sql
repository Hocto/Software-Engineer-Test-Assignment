-- Core banking schema.
--
-- Money is stored as NUMERIC(19,4) and never as a floating point type: binary floating
-- point cannot represent decimal fractions exactly, which is unacceptable for balances.
-- Java side maps to BigDecimal throughout.

CREATE TABLE account
(
    id          BIGSERIAL PRIMARY KEY,
    customer_id BIGINT      NOT NULL,
    country     VARCHAR(64) NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_account_customer_id ON account (customer_id);

CREATE TABLE balance
(
    id               BIGSERIAL PRIMARY KEY,
    account_id       BIGINT         NOT NULL REFERENCES account (id),
    currency         VARCHAR(3)     NOT NULL,
    available_amount NUMERIC(19, 4) NOT NULL DEFAULT 0,
    -- Retained for auditing and as an optimistic-locking escape hatch. The write path
    -- uses SELECT ... FOR UPDATE, so this column is informational today.
    version          BIGINT         NOT NULL DEFAULT 0,
    created_at       TIMESTAMPTZ    NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ    NOT NULL DEFAULT now(),
    CONSTRAINT uq_balance_account_currency UNIQUE (account_id, currency),
    CONSTRAINT ck_balance_currency CHECK (currency IN ('EUR', 'SEK', 'GBP', 'USD')),
    -- Database-level backstop behind the application's sufficient-funds check.
    CONSTRAINT ck_balance_not_negative CHECK (available_amount >= 0)
);

CREATE INDEX idx_balance_account_id ON balance (account_id);

CREATE TABLE transaction
(
    id            BIGSERIAL PRIMARY KEY,
    account_id    BIGINT         NOT NULL REFERENCES account (id),
    amount        NUMERIC(19, 4) NOT NULL,
    currency      VARCHAR(3)     NOT NULL,
    direction     VARCHAR(3)     NOT NULL,
    description   TEXT           NOT NULL,
    balance_after NUMERIC(19, 4) NOT NULL,
    created_at    TIMESTAMPTZ    NOT NULL DEFAULT now(),
    CONSTRAINT ck_transaction_currency CHECK (currency IN ('EUR', 'SEK', 'GBP', 'USD')),
    CONSTRAINT ck_transaction_direction CHECK (direction IN ('IN', 'OUT')),
    CONSTRAINT ck_transaction_amount_positive CHECK (amount > 0)
);

-- Supports "list transactions for an account" in insertion order.
CREATE INDEX idx_transaction_account_id ON transaction (account_id, id);
