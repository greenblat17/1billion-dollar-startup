CREATE TABLE legacy_voice_preferences (
    session_id TEXT PRIMARY KEY,
    character TEXT,
    speech_speed DOUBLE PRECISION
);
CREATE TABLE legacy_speech_rollout (
    singleton BOOLEAN PRIMARY KEY DEFAULT TRUE CHECK (singleton),
    activation_day DATE NOT NULL
);
CREATE TABLE legacy_audit_artifact (
    attempt_id TEXT PRIMARY KEY,
    transcript TEXT,
    reply TEXT,
    reply_draft TEXT,
    conversation_plan TEXT,
    expires_at TIMESTAMPTZ NOT NULL
);
CREATE INDEX legacy_audit_artifact_expiry ON legacy_audit_artifact(expires_at);
