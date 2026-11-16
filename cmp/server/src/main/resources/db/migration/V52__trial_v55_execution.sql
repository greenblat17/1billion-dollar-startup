-- Trial execution/transport facts only. Observation and Learning Plan belong to other domains.
ALTER TABLE lesson ADD COLUMN trial_version TEXT;
ALTER TABLE lesson ADD COLUMN trial_phase TEXT NOT NULL DEFAULT 'warmup'
    CHECK (trial_phase IN ('warmup','conversation','wrapup'));
ALTER TABLE call_session ADD COLUMN trial_ready_at TIMESTAMPTZ;
ALTER TABLE call_session ADD COLUMN trial_seen_at TIMESTAMPTZ;
CREATE TABLE trial_call_event (
    id TEXT PRIMARY KEY,
    student_id TEXT NOT NULL REFERENCES student(id),
    lesson_id TEXT NOT NULL REFERENCES lesson(id) ON DELETE CASCADE,
    call_session_id TEXT NOT NULL REFERENCES call_session(id) ON DELETE CASCADE,
    source_key TEXT NOT NULL,
    source_event_id TEXT REFERENCES conversation_event(id) ON DELETE CASCADE,
    revision BIGINT NOT NULL CHECK (revision > 0),
    kind TEXT NOT NULL CHECK (kind IN ('transcript','phase','connected','ended')),
    occurred_at TIMESTAMPTZ NOT NULL,
    metadata JSONB NOT NULL,
    input_digest TEXT NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    UNIQUE(call_session_id, source_key)
);
CREATE INDEX trial_call_event_read ON trial_call_event(student_id, lesson_id, occurred_at, id);
