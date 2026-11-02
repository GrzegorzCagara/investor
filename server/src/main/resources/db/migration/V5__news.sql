ALTER TABLE report ADD COLUMN event_type TEXT NOT NULL DEFAULT 'RESULTS';

CREATE TABLE news_assessment (
    report_id BIGINT PRIMARY KEY REFERENCES report (id) ON DELETE CASCADE,
    category TEXT NOT NULL,
    direction TEXT NOT NULL,
    impact DOUBLE PRECISION NOT NULL,
    reason TEXT NOT NULL,
    contract_value NUMERIC(20, 3),
    contract_currency TEXT,
    revenue_share_pct DOUBLE PRECISION,
    created_at TIMESTAMPTZ NOT NULL
);
