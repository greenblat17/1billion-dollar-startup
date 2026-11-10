-- Legacy voice state stays separate from canonical Student memory.
CREATE TABLE legacy_learner (
    session_id TEXT PRIMARY KEY,
    onboarding JSONB,
    assessment JSONB,
    memory JSONB,
    practice_minutes INT,
    revision BIGINT NOT NULL DEFAULT 0
);
CREATE TABLE legacy_vocabulary_trace (
    flow TEXT NOT NULL CHECK (flow IN ('onboarding','call')),
    review_id TEXT NOT NULL,
    payload JSONB NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY(flow, review_id)
);
CREATE INDEX legacy_vocabulary_trace_expiry ON legacy_vocabulary_trace(expires_at);
