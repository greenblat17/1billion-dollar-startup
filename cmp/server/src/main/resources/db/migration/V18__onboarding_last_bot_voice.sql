CREATE TABLE onboarding_last_bot_voice (
    run_id TEXT PRIMARY KEY,
    message_id BIGINT NOT NULL,
    delivered_at TIMESTAMPTZ NOT NULL
);
