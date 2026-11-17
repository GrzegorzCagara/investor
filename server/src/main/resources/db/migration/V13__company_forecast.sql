CREATE TABLE company_forecast (
    id BIGSERIAL PRIMARY KEY,
    company_id BIGINT NOT NULL REFERENCES company (id) ON DELETE CASCADE,
    report_id BIGINT REFERENCES report (id) ON DELETE CASCADE,
    period_end DATE NOT NULL,
    currency TEXT NOT NULL,
    revenue NUMERIC(20, 3),
    operating_profit NUMERIC(20, 3),
    net_profit NUMERIC(20, 3),
    published_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX company_forecast_year ON company_forecast (company_id, period_end, published_at);
CREATE UNIQUE INDEX company_forecast_report_year ON company_forecast (report_id, period_end);
