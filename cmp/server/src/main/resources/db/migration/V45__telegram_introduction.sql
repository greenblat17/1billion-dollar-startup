-- Existing students keep their relationship; never enroll them in a new introduction.
ALTER TABLE student ADD COLUMN telegram_intro_eligible BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE student ALTER COLUMN telegram_intro_eligible SET DEFAULT TRUE;

CREATE TABLE telegram_introduction (
    student_id TEXT PRIMARY KEY REFERENCES student(id),
    chat_id BIGINT NOT NULL UNIQUE,
    request_id TEXT NOT NULL,
    message_id BIGINT NOT NULL,
    input_digest TEXT NOT NULL,
    data_epoch BIGINT NOT NULL,
    status TEXT NOT NULL CHECK(status IN ('active','completed','cancelled','unknown')),
    next_part INTEGER NOT NULL DEFAULT 0 CHECK(next_part BETWEEN 0 AND 4),
    due_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL
);
CREATE TABLE telegram_intro_delivery (
    student_id TEXT NOT NULL REFERENCES telegram_introduction(student_id) ON DELETE CASCADE,
    part INTEGER NOT NULL CHECK(part BETWEEN 0 AND 3),
    outcome TEXT NOT NULL CHECK(outcome IN ('unknown','sent')),
    attempted_at TIMESTAMPTZ NOT NULL,
    message_id BIGINT,
    PRIMARY KEY(student_id,part)
);
