-- Consumer-side deduplication table.
-- Before processing any Kafka message, the consumer inserts here.
-- If the insert violates the PK constraint, the event is a duplicate and is skipped.
CREATE TABLE IF NOT EXISTS processed_events (
    event_id        UUID        PRIMARY KEY,
    processed_at    TIMESTAMPTZ DEFAULT NOW()
);

-- TTL-based cleanup: events older than 7 days can be purged by a maintenance job
CREATE INDEX IF NOT EXISTS idx_processed_events_time ON processed_events (processed_at DESC);
