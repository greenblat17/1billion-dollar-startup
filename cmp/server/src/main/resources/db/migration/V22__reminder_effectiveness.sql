ALTER TABLE reminder_buttons
    ADD COLUMN source TEXT CHECK (source IN ('configured_practice', 'inferred_practice', 'onboarding')),
    ADD COLUMN copy_kind TEXT CHECK (copy_kind IN ('general', 'personalized')),
    ADD COLUMN clicked_at TIMESTAMPTZ;

CREATE INDEX reminder_buttons_source_time_idx ON reminder_buttons (source, created_at)
    WHERE source IS NOT NULL;

ALTER TABLE onboarding_nudges ADD COLUMN preferred_utc_minute SMALLINT
    CHECK (preferred_utc_minute BETWEEN 0 AND 1439);
CREATE INDEX onboarding_nudges_day_sent_idx ON onboarding_nudges (day, sent_at)
    WHERE status = 'sent';

CREATE TABLE configured_practice_attempts (
    session_id TEXT NOT NULL,
    day DATE NOT NULL,
    recorded_at TIMESTAMPTZ NOT NULL,
    status TEXT NOT NULL CHECK (status IN ('sent', 'blocked', 'failed', 'unknown')),
    error_code TEXT,
    PRIMARY KEY (session_id, day)
);
CREATE INDEX configured_practice_attempts_time_idx ON configured_practice_attempts (recorded_at);
