CREATE TABLE reminder_delivery (
    job_id TEXT PRIMARY KEY REFERENCES foundation_job(id), student_id TEXT NOT NULL REFERENCES student(id),
    lesson_id TEXT NOT NULL REFERENCES lesson(id), lesson_version BIGINT NOT NULL,
    outcome TEXT NOT NULL CHECK(outcome IN ('unknown','sent','not_sent')), receipt TEXT,
    attempted_at TIMESTAMPTZ NOT NULL
);
