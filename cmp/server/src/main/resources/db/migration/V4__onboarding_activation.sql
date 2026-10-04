ALTER TABLE onboarding_attempts
    ADD COLUMN begin_pressed_at TIMESTAMPTZ,
    ADD COLUMN first_question_delivered_at TIMESTAMPTZ;

CREATE TABLE onboarding_entries (
    session_id TEXT NOT NULL,
    entry_key TEXT NOT NULL,
    received_at TIMESTAMPTZ NOT NULL,
    eligible BOOLEAN NOT NULL,
    trigger TEXT NOT NULL,
    exclusion_reason TEXT,
    start_source TEXT,
    run_id TEXT,
    invitation_delivered_at TIMESTAMPTZ,
    PRIMARY KEY (session_id, entry_key)
);

CREATE INDEX onboarding_entries_received_idx ON onboarding_entries (received_at, eligible);
CREATE INDEX onboarding_entries_run_idx ON onboarding_entries (run_id);
CREATE INDEX onboarding_entries_first_eligible_idx ON onboarding_entries (session_id, received_at, entry_key)
    WHERE eligible = TRUE AND trigger = 'start';

CREATE TABLE onboarding_practice_days (
    primary_run_id TEXT NOT NULL REFERENCES onboarding_attempts (run_id),
    practice_day DATE NOT NULL,
    first_reply_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (primary_run_id, practice_day)
);
