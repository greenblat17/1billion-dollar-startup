ALTER TABLE onboarding_attempts
    ADD COLUMN post_completion_practice_observable BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN first_post_completion_practice_at TIMESTAMPTZ;

CREATE INDEX onboarding_events_recovery_idx
    ON onboarding_events (attempt_id, event_type, failure_stage, created_at)
    WHERE event_type IN ('action_attempt', 'retry_requested');
