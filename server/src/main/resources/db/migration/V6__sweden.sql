ALTER TABLE company ADD COLUMN avanza_id TEXT;
ALTER TABLE company ADD COLUMN listing TEXT;
ALTER TABLE company ADD COLUMN quote_currency TEXT;

DROP INDEX company_isin_uq;
CREATE UNIQUE INDEX company_market_isin_uq ON company (market, isin) WHERE isin IS NOT NULL;

CREATE INDEX report_company_number_published_idx ON report (company_id, number, published_at);
