CREATE TABLE report_context (
    report_id BIGINT PRIMARY KEY REFERENCES report (id) ON DELETE CASCADE,
    points JSONB NOT NULL,
    headlines JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL
);
