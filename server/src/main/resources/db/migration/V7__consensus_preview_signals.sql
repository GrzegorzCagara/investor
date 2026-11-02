CREATE TABLE consensus_preview (
    id BIGSERIAL PRIMARY KEY,
    url TEXT NOT NULL UNIQUE,
    isin TEXT NOT NULL,
    company_name TEXT NOT NULL,
    report_date DATE NOT NULL,
    currency TEXT NOT NULL,
    revenue_consensus NUMERIC(20, 2),
    revenue_prev_year NUMERIC(20, 2),
    revenue_analysts INT,
    ebit_consensus NUMERIC(20, 2),
    ebit_prev_year NUMERIC(20, 2),
    ebit_analysts INT,
    source TEXT NOT NULL,
    past_reactions JSONB NOT NULL,
    published_at TIMESTAMPTZ NOT NULL,
    fetched_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX consensus_preview_isin_report_date_idx ON consensus_preview (isin, report_date);

ALTER TABLE score ADD COLUMN signals TEXT[] NOT NULL DEFAULT '{}';
ALTER TABLE score ADD COLUMN context JSONB;
