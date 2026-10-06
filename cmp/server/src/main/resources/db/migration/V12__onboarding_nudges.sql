CREATE TABLE onboarding_nudges (
    session_id TEXT NOT NULL,
    day DATE NOT NULL,
    run_id TEXT NOT NULL REFERENCES onboarding_attempts(run_id),
    claimed_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (session_id, day)
);

CREATE INDEX onboarding_nudges_claimed_idx ON onboarding_nudges (claimed_at);
