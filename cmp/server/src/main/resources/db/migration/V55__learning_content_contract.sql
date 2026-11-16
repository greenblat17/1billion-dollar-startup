-- SP-13A stores the exact material version presented for a Task Stage.
-- Generation, answer evaluation and scoring belong to later SPs.
CREATE TABLE learning_content (
    id TEXT PRIMARY KEY,
    student_id TEXT NOT NULL,
    task_id TEXT NOT NULL,
    current_revision BIGINT NOT NULL CHECK (current_revision > 0),
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    UNIQUE (student_id, id),
    FOREIGN KEY (student_id, task_id) REFERENCES learning_task(student_id, id) ON DELETE CASCADE
);

CREATE TABLE learning_content_revision (
    content_id TEXT NOT NULL REFERENCES learning_content(id) ON DELETE CASCADE,
    revision BIGINT NOT NULL CHECK (revision > 0),
    document JSONB NOT NULL CHECK (jsonb_typeof(document) = 'object'),
    status TEXT NOT NULL CHECK (status IN ('prepared', 'issued', 'defective')),
    component TEXT NOT NULL,
    reason TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    issued_at TIMESTAMPTZ,
    issued_lesson_id TEXT,
    defect_reason TEXT,
    defect_component TEXT,
    defect_at TIMESTAMPTZ,
    PRIMARY KEY (content_id, revision),
    CHECK ((issued_at IS NULL AND issued_lesson_id IS NULL) OR
           (issued_at IS NOT NULL AND issued_lesson_id IS NOT NULL)),
    CHECK (status <> 'prepared' OR issued_at IS NULL),
    CHECK ((status = 'defective') = (defect_reason IS NOT NULL AND defect_component IS NOT NULL AND defect_at IS NOT NULL))
);

ALTER TABLE learning_content ADD CONSTRAINT learning_content_current_revision
    FOREIGN KEY (id, current_revision) REFERENCES learning_content_revision(content_id, revision)
    DEFERRABLE INITIALLY DEFERRED;
CREATE INDEX learning_content_student_task ON learning_content(student_id, task_id, created_at);
