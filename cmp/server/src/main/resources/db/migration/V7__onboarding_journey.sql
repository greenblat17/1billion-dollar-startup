ALTER TABLE onboarding_entries
    ADD COLUMN telegram_chat_id BIGINT,
    ADD COLUMN telegram_username TEXT,
    ADD COLUMN invitation_error_reason TEXT;

ALTER TABLE onboarding_attempts
    ADD COLUMN telegram_chat_id BIGINT,
    ADD COLUMN telegram_username TEXT,
    ADD COLUMN invitation_delivered_at TIMESTAMPTZ,
    ADD COLUMN invitation_error_reason TEXT;

ALTER TABLE onboarding_voices
    ADD COLUMN failure_stage TEXT,
    ADD COLUMN failure_code TEXT;

ALTER TABLE onboarding_events
    ADD COLUMN failure_stage TEXT,
    ADD COLUMN failure_code TEXT;

CREATE INDEX onboarding_attempts_recent_idx
    ON onboarding_attempts (started_at DESC, session_id);
CREATE INDEX onboarding_entries_recent_idx
    ON onboarding_entries (received_at DESC, session_id)
    WHERE eligible = TRUE AND trigger = 'start';
