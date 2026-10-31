CREATE TABLE report_outcome (
    report_id BIGINT PRIMARY KEY REFERENCES report (id) ON DELETE CASCADE,
    base_date DATE NOT NULL,
    reaction_pct DOUBLE PRECISION,
    reaction_index_pct DOUBLE PRECISION,
    volume_multiple DOUBLE PRECISION,
    return5_pct DOUBLE PRECISION,
    index5_pct DOUBLE PRECISION,
    return20_pct DOUBLE PRECISION,
    index20_pct DOUBLE PRECISION,
    return60_pct DOUBLE PRECISION,
    index60_pct DOUBLE PRECISION,
    updated_at TIMESTAMPTZ NOT NULL
);
