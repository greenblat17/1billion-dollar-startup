-- Initial SP-08 route. Task lifecycle and progress remain in learning_task.
CREATE TABLE learning_roadmap (
    student_id TEXT PRIMARY KEY REFERENCES student(id) ON DELETE CASCADE,
    revision BIGINT NOT NULL CHECK (revision > 0),
    goal_revision BIGINT NOT NULL,
    learning_revision BIGINT NOT NULL,
    knowledge_digest TEXT NOT NULL,
    catalog_version TEXT NOT NULL,
    document JSONB NOT NULL CHECK (jsonb_typeof(document) = 'object'),
    created_at TIMESTAMPTZ NOT NULL,
    FOREIGN KEY (student_id, goal_revision) REFERENCES learning_goal(student_id, revision),
    FOREIGN KEY (catalog_version) REFERENCES learning_catalog_release(version)
);
CREATE TABLE learning_roadmap_history (
    student_id TEXT NOT NULL REFERENCES learning_roadmap(student_id) ON DELETE CASCADE,
    revision BIGINT NOT NULL,
    goal_revision BIGINT NOT NULL,
    learning_revision BIGINT NOT NULL,
    knowledge_digest TEXT NOT NULL,
    catalog_version TEXT NOT NULL,
    document JSONB NOT NULL CHECK (jsonb_typeof(document) = 'object'),
    recorded_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (student_id, revision)
);
