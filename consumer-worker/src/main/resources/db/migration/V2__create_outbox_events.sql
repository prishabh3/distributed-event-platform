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

CREATE INDEX IF NOT EXISTS idx_outbox_unpublished ON outbox_events (created_at ASC)
    WHERE published = FALSE;
