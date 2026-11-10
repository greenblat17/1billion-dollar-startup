CREATE TABLE lesson_telegram_card (
    student_id TEXT NOT NULL,
    lesson_id TEXT PRIMARY KEY,
    message_id BIGINT NOT NULL CHECK(message_id>0),
    lesson_version BIGINT NOT NULL,
    FOREIGN KEY(student_id,lesson_id) REFERENCES lesson(student_id,id)
);
CREATE TABLE foundation_cutover (
    singleton BOOLEAN PRIMARY KEY DEFAULT TRUE CHECK(singleton),
    manifest_digest TEXT NOT NULL,
    completed_at TIMESTAMPTZ NOT NULL,
    source_frozen_at TIMESTAMPTZ NOT NULL
);
