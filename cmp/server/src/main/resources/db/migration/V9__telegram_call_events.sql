CREATE TABLE telegram_call_events (
    event_id TEXT PRIMARY KEY,
    call_id CHAR(32),
    chat_id BIGINT NOT NULL,
    username TEXT NOT NULL DEFAULT '',
    kind TEXT NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL,
    message_id BIGINT,
    bot_message_id BIGINT,
    milliseconds BIGINT,
    seconds DOUBLE PRECISION,
    amount INTEGER,
    state TEXT
);

CREATE INDEX telegram_call_events_call_idx ON telegram_call_events (call_id, occurred_at) WHERE call_id IS NOT NULL;
CREATE INDEX telegram_call_events_time_idx ON telegram_call_events (occurred_at DESC);
CREATE INDEX telegram_call_events_bot_idx ON telegram_call_events (chat_id, bot_message_id) WHERE bot_message_id IS NOT NULL;
