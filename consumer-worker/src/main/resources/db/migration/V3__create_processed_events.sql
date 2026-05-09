CREATE TABLE IF NOT EXISTS processed_events (
    event_id        UUID        PRIMARY KEY,
    processed_at    TIMESTAMPTZ DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_processed_events_time ON processed_events (processed_at DESC);
