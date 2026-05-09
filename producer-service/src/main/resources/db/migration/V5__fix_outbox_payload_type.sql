ALTER TABLE outbox_events ALTER COLUMN payload TYPE json USING payload::json;
