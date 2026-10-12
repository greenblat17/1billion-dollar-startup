ALTER TABLE onboarding_attempts
    ADD COLUMN start_source TEXT,
    ADD COLUMN result_delivered_at TIMESTAMPTZ,
    ADD COLUMN reminder_offered_at TIMESTAMPTZ,
    ADD COLUMN profile_opened_at TIMESTAMPTZ,
    ADD COLUMN bye_at TIMESTAMPTZ,
    ADD COLUMN first_practice_at TIMESTAMPTZ,
    ADD COLUMN d7_voice_at TIMESTAMPTZ,
    ADD COLUMN grammar_examples_count INTEGER,
    ADD COLUMN vocabulary_examples_count INTEGER,
    ADD COLUMN fluency_metrics_available BOOLEAN;

ALTER TABLE onboarding_voices
    ADD COLUMN outcome TEXT,
    ADD COLUMN speech_before_sec DOUBLE PRECISION,
    ADD COLUMN speech_after_sec DOUBLE PRECISION,
    ADD COLUMN received_at TIMESTAMPTZ;

UPDATE onboarding_voices
SET outcome = CASE
    WHEN failure_reason = 'no_speech' THEN 'no_speech'
    WHEN failure_reason IS NOT NULL THEN 'unknown'
    WHEN recognized THEN 'recognized'
    ELSE 'unknown'
END;
ALTER TABLE onboarding_voices ALTER COLUMN outcome SET NOT NULL;

CREATE TABLE onboarding_events (
    attempt_id TEXT NOT NULL REFERENCES onboarding_attempts (run_id),
    event_key TEXT NOT NULL,
    event_type TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (attempt_id, event_key)
);

CREATE INDEX onboarding_events_type_created_idx ON onboarding_events (event_type, created_at);
CREATE INDEX onboarding_attempts_cohort_idx ON onboarding_attempts (started_at, is_primary, onboarding_version, trigger);
