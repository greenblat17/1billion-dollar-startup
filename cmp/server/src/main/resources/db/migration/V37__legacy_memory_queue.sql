CREATE TABLE legacy_memory_job (
    id TEXT PRIMARY KEY, session_id TEXT NOT NULL, position BIGSERIAL NOT NULL UNIQUE,
    payload JSONB, created_at TIMESTAMPTZ NOT NULL, expires_at TIMESTAMPTZ NOT NULL,
    done_until TIMESTAMPTZ, lease_token TEXT, lease_until TIMESTAMPTZ
);
CREATE INDEX legacy_memory_job_head ON legacy_memory_job(session_id,position) WHERE done_until IS NULL;
