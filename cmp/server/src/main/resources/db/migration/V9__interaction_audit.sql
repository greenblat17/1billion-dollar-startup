CREATE TABLE interaction_events (
    event_id TEXT PRIMARY KEY,
    chat_id BIGINT,
    update_id BIGINT,
    message_id BIGINT,
    attempt_id UUID,
    job_id TEXT,
    direction TEXT NOT NULL,
    kind TEXT NOT NULL,
    status TEXT NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL,
    received_at TIMESTAMPTZ NOT NULL,
    reconcile_count INTEGER NOT NULL DEFAULT 0,
    reconciled_at TIMESTAMPTZ
);

CREATE TABLE interaction_content (
    event_id TEXT PRIMARY KEY REFERENCES interaction_events(event_id) ON DELETE CASCADE,
    content TEXT,
    audio_file TEXT,
    audio_size INTEGER,
    audio_sha256 CHAR(64)
);

CREATE INDEX interaction_events_chat_time_idx ON interaction_events (chat_id, occurred_at DESC, event_id);
CREATE INDEX interaction_events_attempt_idx ON interaction_events (attempt_id, occurred_at);
CREATE INDEX interaction_events_received_idx ON interaction_events (received_at);
