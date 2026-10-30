CREATE TABLE company (
    id BIGSERIAL PRIMARY KEY,
    name TEXT NOT NULL,
    isin TEXT,
    market TEXT NOT NULL,
    ticker TEXT,
    biznesradar_symbol TEXT,
    publishes_monthly_sales BOOLEAN NOT NULL DEFAULT FALSE,
    short_name TEXT
);

CREATE UNIQUE INDEX company_isin_uq ON company (isin) WHERE isin IS NOT NULL;
CREATE INDEX company_name_idx ON company (name);

CREATE TABLE report (
    id BIGSERIAL PRIMARY KEY,
    company_id BIGINT NOT NULL REFERENCES company (id),
    market TEXT NOT NULL,
    kind TEXT NOT NULL,
    number TEXT NOT NULL,
    source_ref TEXT NOT NULL,
    title TEXT,
    published_at TIMESTAMPTZ NOT NULL,
    detected_at TIMESTAMPTZ NOT NULL,
    detail_url TEXT NOT NULL,
    status TEXT NOT NULL,
    last_attempt_at TIMESTAMPTZ,
    status_changed_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT report_market_source_ref_uq UNIQUE (market, source_ref)
);

CREATE INDEX report_status_idx ON report (status);
CREATE INDEX report_published_at_idx ON report (published_at);

CREATE TABLE extraction (
    report_id BIGINT PRIMARY KEY REFERENCES report (id),
    payload JSONB NOT NULL,
    uncertain_fields TEXT[] NOT NULL DEFAULT '{}'
);

CREATE TABLE score (
    report_id BIGINT PRIMARY KEY REFERENCES report (id),
    total NUMERIC(6, 2) NOT NULL,
    components JSONB NOT NULL,
    penalty NUMERIC(6, 2) NOT NULL DEFAULT 0,
    warnings TEXT[] NOT NULL DEFAULT '{}',
    reason TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE push_log (
    id BIGSERIAL PRIMARY KEY,
    report_id BIGINT REFERENCES report (id),
    kind TEXT NOT NULL,
    title TEXT NOT NULL,
    body TEXT NOT NULL,
    sent_at TIMESTAMPTZ NOT NULL,
    company_id BIGINT REFERENCES company (id),
    period_kind TEXT,
    period_end DATE
);

CREATE UNIQUE INDEX push_log_results_period_uq ON push_log (company_id, period_kind, period_end) WHERE kind = 'RESULTS';

CREATE TABLE device (
    token TEXT PRIMARY KEY,
    registered_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE source_health (
    source TEXT PRIMARY KEY,
    last_success_at TIMESTAMPTZ,
    last_failure_at TIMESTAMPTZ,
    last_error TEXT,
    outage_notified BOOLEAN NOT NULL DEFAULT FALSE
);

CREATE TABLE ai_usage (
    month DATE NOT NULL,
    pool TEXT NOT NULL,
    input_tokens BIGINT NOT NULL DEFAULT 0,
    output_tokens BIGINT NOT NULL DEFAULT 0,
    cost_usd NUMERIC(10, 6) NOT NULL DEFAULT 0,
    PRIMARY KEY (month, pool)
);

CREATE TABLE setting (
    key TEXT PRIMARY KEY,
    value TEXT NOT NULL
);

INSERT INTO setting (key, value) VALUES ('PUSH_THRESHOLD', '70');

CREATE TABLE revenue_period (
    company_id BIGINT NOT NULL REFERENCES company (id),
    kind TEXT NOT NULL,
    period_end DATE NOT NULL,
    currency TEXT NOT NULL,
    revenue NUMERIC(20, 3) NOT NULL,
    revenue_prev_year NUMERIC(20, 3),
    published_at TIMESTAMPTZ NOT NULL,
    report_id BIGINT REFERENCES report (id),
    PRIMARY KEY (company_id, kind, period_end)
);

CREATE TABLE quarter_result (
    company_id BIGINT NOT NULL REFERENCES company (id),
    quarter_end DATE NOT NULL,
    revenue NUMERIC(20, 3),
    operating_profit NUMERIC(20, 3),
    net_profit NUMERIC(20, 3),
    published_on DATE,
    PRIMARY KEY (company_id, quarter_end)
);

CREATE TABLE valuation_point (
    company_id BIGINT NOT NULL REFERENCES company (id),
    quarter_end DATE NOT NULL,
    price NUMERIC(20, 4),
    shares BIGINT,
    pe NUMERIC(12, 4),
    ps NUMERIC(12, 4),
    PRIMARY KEY (company_id, quarter_end)
);

CREATE TABLE daily_quote (
    instrument TEXT NOT NULL,
    date DATE NOT NULL,
    close NUMERIC(20, 4) NOT NULL,
    volume BIGINT NOT NULL,
    turnover NUMERIC(20, 2),
    PRIMARY KEY (instrument, date)
);
