-- Change payload columns from jsonb to json.
-- Hibernate sends VARCHAR for plain String and Map<String,Object> fields;
-- PostgreSQL json (unlike jsonb) accepts plain text input from JDBC.
ALTER TABLE events ALTER COLUMN payload TYPE json USING payload::json;
ALTER TABLE outbox_events ALTER COLUMN payload TYPE json USING payload::json;
