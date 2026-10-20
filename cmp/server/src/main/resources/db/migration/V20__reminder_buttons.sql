CREATE TABLE reminder_buttons (
    token UUID PRIMARY KEY,
    session_id TEXT NOT NULL,
    chat_id BIGINT NOT NULL,
    message_id BIGINT NOT NULL,
    opening_question TEXT,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    markup_cleared_at TIMESTAMPTZ,
    UNIQUE (chat_id, message_id)
);

CREATE UNIQUE INDEX reminder_buttons_one_active_idx ON reminder_buttons (chat_id) WHERE active;
CREATE INDEX reminder_buttons_to_clear_idx ON reminder_buttons (created_at)
    WHERE active = FALSE AND markup_cleared_at IS NULL;
