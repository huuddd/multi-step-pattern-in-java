-- V1: Create payments table
CREATE TABLE payments (
    payment_id       TEXT PRIMARY KEY,
    merchant_id      TEXT NOT NULL,
    amount           BIGINT NOT NULL,
    currency         TEXT NOT NULL,
    card_bin         TEXT,
    ip               TEXT,
    device_id        TEXT,
    idempotency_key  TEXT NOT NULL,
    state            TEXT NOT NULL DEFAULT 'PENDING',
    decision         TEXT,
    risk_score       NUMERIC(5,4),
    created_at       TIMESTAMPTZ DEFAULT now(),
    updated_at       TIMESTAMPTZ DEFAULT now(),
    
    CONSTRAINT chk_state CHECK (state IN ('PENDING', 'DECIDED', 'REVIEW', 'BLOCKED')),
    CONSTRAINT chk_decision CHECK (decision IS NULL OR decision IN ('ALLOW', 'REVIEW', 'BLOCK')),
    CONSTRAINT uq_merchant_idempotency UNIQUE (merchant_id, idempotency_key)
);

-- Indexes for common queries
CREATE INDEX idx_payments_merchant_id ON payments(merchant_id);
CREATE INDEX idx_payments_state ON payments(state);
CREATE INDEX idx_payments_created_at ON payments(created_at);
