CREATE TABLE onboarding_attempts (
    run_id TEXT PRIMARY KEY,
    session_id TEXT NOT NULL,
    attempt_number INTEGER NOT NULL,
    trigger TEXT NOT NULL,
    is_primary BOOLEAN NOT NULL,
    started_at TIMESTAMPTZ NOT NULL,
    lets_chat_at TIMESTAMPTZ,
    first_voice_at TIMESTAMPTZ,
    speech_30_at TIMESTAMPTZ,
    speech_60_at TIMESTAMPTZ,
    speech_90_at TIMESTAMPTZ,
    speech_120_at TIMESTAMPTZ,
    completed_at TIMESTAMPTZ,
    results_opened_at TIMESTAMPTZ,
    grammar_viewed_at TIMESTAMPTZ,
    vocabulary_viewed_at TIMESTAMPTZ,
    fluency_viewed_at TIMESTAMPTZ,
    practice_setup_at TIMESTAMPTZ,
    goal_minutes INTEGER,
    goal_selected_at TIMESTAMPTZ,
    reminder_decision TEXT,
    reminder_decision_at TIMESTAMPTZ,
    reminder_set_at TIMESTAMPTZ,
    cefr TEXT,
    overall_score INTEGER,
    score_available BOOLEAN,
    assessment_failed_at TIMESTAMPTZ,
    d1_voice_at TIMESTAMPTZ,
    onboarding_version TEXT NOT NULL DEFAULT 'v1'
);

CREATE INDEX onboarding_attempts_session_idx ON onboarding_attempts (session_id);
CREATE INDEX onboarding_attempts_started_idx ON onboarding_attempts (started_at);

CREATE TABLE onboarding_voices (
    attempt_id TEXT NOT NULL REFERENCES onboarding_attempts (run_id),
    request_id TEXT NOT NULL,
    session_id TEXT NOT NULL,
    voice_index INTEGER NOT NULL,
    telegram_duration_sec DOUBLE PRECISION NOT NULL DEFAULT 0,
    recognized_duration_sec DOUBLE PRECISION NOT NULL DEFAULT 0,
    recognized BOOLEAN NOT NULL,
    failure_reason TEXT,
    processing_ms INTEGER NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (attempt_id, request_id)
);

CREATE INDEX onboarding_voices_created_idx ON onboarding_voices (created_at);
