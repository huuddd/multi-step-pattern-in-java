-- V2: Create risk_events table for pipeline audit log
CREATE TABLE risk_events (
    id          BIGSERIAL PRIMARY KEY,
    payment_id  TEXT NOT NULL REFERENCES payments(payment_id) ON DELETE CASCADE,
    step        TEXT NOT NULL,
    status      TEXT NOT NULL,
    detail      JSONB,
    created_at  TIMESTAMPTZ DEFAULT now(),
    
    CONSTRAINT chk_step CHECK (step IN ('INGEST', 'FEATURE', 'MODEL', 'RULE', 'DECISION', 'WEBHOOK')),
    CONSTRAINT chk_status CHECK (status IN ('RUNNING', 'DONE', 'FAILED', 'RETRIED', 'SKIPPED'))
);

-- Indexes for common queries
CREATE INDEX idx_risk_events_payment_id ON risk_events(payment_id);
CREATE INDEX idx_risk_events_step_status ON risk_events(step, status);
CREATE INDEX idx_risk_events_created_at ON risk_events(created_at);

-- Unique constraint for idempotency per step
CREATE UNIQUE INDEX uq_risk_events_payment_step_done 
    ON risk_events(payment_id, step) 
    WHERE status = 'DONE';
