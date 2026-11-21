CREATE TABLE post_lesson_review (
    student_id TEXT NOT NULL,
    lesson_id TEXT NOT NULL,
    revision BIGINT NOT NULL CHECK (revision > 0),
    transcript_revision BIGINT NOT NULL,
    evidence_digest TEXT NOT NULL,
    document JSONB NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (student_id, lesson_id),
    FOREIGN KEY (student_id, lesson_id) REFERENCES lesson(student_id, id) ON DELETE CASCADE
);

CREATE TABLE post_lesson_review_history (
    student_id TEXT NOT NULL,
    lesson_id TEXT NOT NULL,
    revision BIGINT NOT NULL,
    transcript_revision BIGINT NOT NULL,
    evidence_digest TEXT NOT NULL,
    document JSONB NOT NULL,
    recorded_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (student_id, lesson_id, revision),
    FOREIGN KEY (student_id, lesson_id) REFERENCES lesson(student_id, id) ON DELETE CASCADE
);
