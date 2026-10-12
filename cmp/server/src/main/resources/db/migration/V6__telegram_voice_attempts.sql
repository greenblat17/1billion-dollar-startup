CREATE TABLE telegram_voice_attempts (
    attempt_id UUID PRIMARY KEY,
    chat_id BIGINT NOT NULL,
    message_id BIGINT NOT NULL,
    username TEXT NOT NULL DEFAULT '',
    received_at TIMESTAMPTZ NOT NULL,
    eligible BOOLEAN,
    outcome TEXT,
    stage TEXT,
    reason TEXT,
    job_id TEXT,
    terminal_at TIMESTAMPTZ,
    setup_ms BIGINT,
    queue_ms BIGINT,
    chat_queue_ms BIGINT,
    download_ms BIGINT,
    processing_ms BIGINT,
    delivery_ms BIGINT,
    total_ms BIGINT,
    stt_ms BIGINT,
    reply_llm_ms BIGINT,
    correction_llm_ms BIGINT,
    tts_ms BIGINT,
    finalize_ms BIGINT,
    UNIQUE (chat_id, message_id)
);

CREATE INDEX telegram_voice_attempts_received_idx ON telegram_voice_attempts (received_at, outcome);
CREATE INDEX telegram_voice_attempts_chat_idx ON telegram_voice_attempts (chat_id, received_at DESC);
