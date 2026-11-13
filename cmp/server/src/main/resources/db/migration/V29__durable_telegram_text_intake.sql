CREATE TABLE telegram_text_intake (
    update_id BIGINT PRIMARY KEY,
    chat_id BIGINT NOT NULL CHECK (chat_id>0),
    message_id BIGINT NOT NULL,
    payload JSONB,
    input_digest TEXT NOT NULL,
    status TEXT NOT NULL DEFAULT 'pending' CHECK (status IN ('pending','running','done','failed')),
    attempts INT NOT NULL DEFAULT 0,
    lease_until TIMESTAMPTZ,
    received_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    UNIQUE(chat_id,message_id)
);
CREATE INDEX telegram_text_intake_pending ON telegram_text_intake(received_at) WHERE status IN ('pending','running');
