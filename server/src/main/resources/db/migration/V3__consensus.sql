CREATE TABLE consensus_note (
    report_id BIGINT PRIMARY KEY REFERENCES report (id) ON DELETE CASCADE,
    url TEXT NOT NULL,
    published_at TIMESTAMPTZ NOT NULL,
    rows JSONB NOT NULL
);
