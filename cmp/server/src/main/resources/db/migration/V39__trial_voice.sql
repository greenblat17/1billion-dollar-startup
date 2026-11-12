ALTER TABLE call_session ADD COLUMN lease_until TIMESTAMPTZ;
ALTER TABLE call_session ADD COLUMN scope_digest TEXT;
CREATE TABLE trial_audio (
    object_key TEXT PRIMARY KEY, student_id TEXT NOT NULL REFERENCES student(id),
    lesson_id TEXT NOT NULL, call_session_id TEXT NOT NULL, data_epoch BIGINT NOT NULL,
    direction TEXT NOT NULL CHECK(direction IN ('user','assistant')), sequence BIGINT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL, expires_at TIMESTAMPTZ NOT NULL,
    metadata JSONB NOT NULL DEFAULT '{}',
    status TEXT NOT NULL DEFAULT 'reserved' CHECK(status IN ('reserved','stored','purge','deleted')),
    UNIQUE(call_session_id,direction,sequence)
);
CREATE TABLE trial_notice (
    id TEXT PRIMARY KEY, student_id TEXT NOT NULL REFERENCES student(id), lesson_id TEXT NOT NULL,
    kind TEXT NOT NULL, due_at TIMESTAMPTZ NOT NULL, data_epoch BIGINT NOT NULL,
    status TEXT NOT NULL DEFAULT 'pending', reply TEXT, lease_until TIMESTAMPTZ, fence BIGINT NOT NULL DEFAULT 0,
    UNIQUE(lesson_id,kind)
);
CREATE TABLE trial_demo_receipt (
    student_id TEXT NOT NULL REFERENCES student(id), schedule_id TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL, PRIMARY KEY(student_id,schedule_id)
);
