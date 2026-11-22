CREATE TABLE legacy_dialogue (
    session_id TEXT PRIMARY KEY,
    messages JSONB NOT NULL DEFAULT '[]',
    expires_at TIMESTAMPTZ NOT NULL,
    revision BIGINT NOT NULL DEFAULT 0,
    generation TEXT NOT NULL DEFAULT md5(random()::text || clock_timestamp()::text),
    lease_until TIMESTAMPTZ,
    fence BIGINT NOT NULL DEFAULT 0
);
CREATE TABLE legacy_dialogue_turn (
    session_id TEXT NOT NULL REFERENCES legacy_dialogue(session_id) ON DELETE CASCADE,
    turn_id TEXT NOT NULL,
    input_digest TEXT NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY(session_id,turn_id)
);
CREATE TABLE legacy_dialogue_rollout (
    singleton BOOLEAN PRIMARY KEY DEFAULT TRUE CHECK(singleton),
    dual_write_until TIMESTAMPTZ NOT NULL
);
