-- Transactional outbox: events written here in the same DB transaction as the events table.
-- A background publisher reads unpublished rows and delivers them to Kafka.
-- Guarantees no event loss even during Kafka downtime.
CREATE TABLE IF NOT EXISTS outbox_events (
    id              BIGSERIAL    PRIMARY KEY,
    event_id        UUID         NOT NULL,
    topic           VARCHAR(255) NOT NULL,
    payload         JSONB        NOT NULL,
    partition_key   VARCHAR(255) NOT NULL,
    published       BOOLEAN               DEFAULT FALSE,
    created_at      TIMESTAMPTZ           DEFAULT NOW(),
    published_at    TIMESTAMPTZ
);

-- Partial index: only unpublished rows need fast lookup
CREATE INDEX IF NOT EXISTS idx_outbox_unpublished ON outbox_events (created_at ASC)
    WHERE published = FALSE;
