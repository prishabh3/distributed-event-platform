ALTER TABLE outbox_events ALTER COLUMN payload TYPE text USING payload::text;
