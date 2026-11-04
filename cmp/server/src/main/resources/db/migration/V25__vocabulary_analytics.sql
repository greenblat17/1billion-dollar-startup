CREATE TABLE vocabulary_analytics_events (
    event_id TEXT PRIMARY KEY,
    occurred_at TIMESTAMPTZ NOT NULL,
    chat_id BIGINT NOT NULL,
    lesson_id TEXT,
    item_id TEXT,
    kind TEXT NOT NULL,
    fact JSONB NOT NULL
);
CREATE INDEX vocabulary_analytics_events_time_idx ON vocabulary_analytics_events (occurred_at DESC);
CREATE INDEX vocabulary_analytics_events_lesson_idx ON vocabulary_analytics_events (lesson_id, occurred_at)
    WHERE lesson_id IS NOT NULL;
CREATE INDEX vocabulary_analytics_events_chat_item_idx ON vocabulary_analytics_events (chat_id, item_id, occurred_at)
    WHERE item_id IS NOT NULL;

CREATE TABLE vocabulary_analytics_cursor (
    source TEXT PRIMARY KEY,
    stream_id TEXT NOT NULL
);
