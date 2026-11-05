ALTER TABLE company ADD COLUMN exchange_ticker TEXT;

UPDATE quarter_result SET published_on = NULL
WHERE published_on = quarter_end
  AND company_id IN (SELECT id FROM company WHERE market IN ('SE', 'DE'));
