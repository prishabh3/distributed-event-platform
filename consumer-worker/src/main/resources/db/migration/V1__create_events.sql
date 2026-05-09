CREATE TABLE IF NOT EXISTS events (
    id              UUID PRIMARY KEY,
    event_type      VARCHAR(50)  NOT NULL,
    partition_key   VARCHAR(255) NOT NULL,
    payload         JSONB        NOT NULL,
    webhook_url     TEXT         NOT NULL,
    status          VARCHAR(20)  NOT NULL DEFAULT 'PENDING',
    retry_count     INT                   DEFAULT 0,
    created_at      TIMESTAMPTZ           DEFAULT NOW(),
    delivered_at    TIMESTAMPTZ,
    error_message   TEXT
);

CREATE INDEX IF NOT EXISTS idx_events_status     ON events (status);
CREATE INDEX IF NOT EXISTS idx_events_created_at ON events (created_at DESC);
CREATE INDEX IF NOT EXISTS idx_events_type       ON events (event_type);
