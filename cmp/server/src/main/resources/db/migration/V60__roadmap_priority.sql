-- SP-09 operational order is independent of SP-08's initial document and SP-10's timeline.
CREATE TABLE learning_roadmap_priority (
    student_id TEXT PRIMARY KEY REFERENCES learning_roadmap(student_id) ON DELETE CASCADE,
    revision BIGINT NOT NULL CHECK (revision > 0),
    roadmap_revision BIGINT NOT NULL CHECK (roadmap_revision > 0),
    document JSONB NOT NULL CHECK (jsonb_typeof(document) = 'object'),
    stale_at TIMESTAMPTZ,
    updated_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE learning_roadmap_priority_history (
    student_id TEXT NOT NULL REFERENCES learning_roadmap(student_id) ON DELETE CASCADE,
    revision BIGINT NOT NULL,
    roadmap_revision BIGINT NOT NULL,
    document JSONB NOT NULL CHECK (jsonb_typeof(document) = 'object'),
    recorded_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (student_id, revision)
);

CREATE TABLE learning_roadmap_problem_decision (
    student_id TEXT NOT NULL REFERENCES learning_roadmap(student_id) ON DELETE CASCADE,
    topic_id TEXT NOT NULL,
    problem_id TEXT NOT NULL,
    decision TEXT NOT NULL CHECK (decision IN ('accepted','declined')),
    task_id TEXT REFERENCES learning_task(id),
    reason TEXT NOT NULL,
    decided_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (student_id, topic_id, problem_id)
);
