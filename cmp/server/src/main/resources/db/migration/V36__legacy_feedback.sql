CREATE TABLE legacy_feedback (
    id TEXT PRIMARY KEY, family TEXT NOT NULL CHECK(family IN ('first','mode')),
    session_id TEXT NOT NULL, call_id TEXT NOT NULL, mode TEXT,
    character TEXT NOT NULL, username TEXT NOT NULL, offered_at TIMESTAMPTZ,
    rating TEXT CHECK(rating IN ('liked','neutral','disliked')), rated_at TIMESTAMPTZ,
    answer TEXT, mirrored BOOLEAN NOT NULL DEFAULT FALSE
);
CREATE INDEX legacy_feedback_rated ON legacy_feedback(rated_at DESC) WHERE rating IS NOT NULL;
CREATE INDEX legacy_feedback_call ON legacy_feedback(session_id,call_id);
CREATE TABLE legacy_feedback_mode_rating (
    session_id TEXT NOT NULL, mode TEXT NOT NULL, instance_id TEXT NOT NULL,
    PRIMARY KEY(session_id,mode)
);
CREATE TABLE legacy_feedback_pending (
    session_id TEXT PRIMARY KEY, feedback_id TEXT NOT NULL REFERENCES legacy_feedback(id) ON DELETE CASCADE,
    expires_at TIMESTAMPTZ NOT NULL
);
CREATE TABLE legacy_feedback_migration (session_id TEXT PRIMARY KEY);
