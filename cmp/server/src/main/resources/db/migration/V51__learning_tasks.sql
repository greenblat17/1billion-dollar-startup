-- Personal work, separate from Topic Knowledge and score calculation.
CREATE TABLE learning_task (
    id TEXT PRIMARY KEY,
    student_id TEXT NOT NULL REFERENCES student(id),
    goal_revision BIGINT NOT NULL,
    catalog_version TEXT NOT NULL,
    skill_id TEXT NOT NULL,
    topic_id TEXT NOT NULL,
    problem_id TEXT NOT NULL,
    revision BIGINT NOT NULL CHECK (revision > 0),
    lifecycle TEXT NOT NULL CHECK (lifecycle IN ('Identified','In Progress','Mastered','Reopened')),
    progress NUMERIC NOT NULL CHECK (progress >= 0 AND progress <= 100),
    details JSONB NOT NULL CHECK (jsonb_typeof(details) = 'object'),
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    UNIQUE (student_id, id),
    FOREIGN KEY (student_id, goal_revision) REFERENCES learning_goal(student_id, revision),
    FOREIGN KEY (catalog_version, skill_id) REFERENCES learning_catalog_skill(version, id),
    FOREIGN KEY (catalog_version, topic_id) REFERENCES learning_catalog_topic(version, id),
    FOREIGN KEY (catalog_version, problem_id) REFERENCES learning_catalog_problem(version, id)
);
CREATE UNIQUE INDEX learning_task_active_problem ON learning_task(student_id, topic_id, problem_id)
    WHERE lifecycle <> 'Mastered';
CREATE INDEX learning_task_student_list ON learning_task(student_id, created_at, id);
CREATE INDEX learning_task_problem_lookup ON learning_task(student_id, topic_id, problem_id);
CREATE TABLE learning_task_history (
    student_id TEXT NOT NULL,
    task_id TEXT NOT NULL,
    revision BIGINT NOT NULL,
    action TEXT NOT NULL,
    component TEXT NOT NULL,
    decision_id TEXT,
    reason TEXT NOT NULL,
    sources JSONB NOT NULL CHECK (jsonb_typeof(sources) = 'array'),
    previous_state JSONB,
    state JSONB NOT NULL,
    recorded_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (student_id, task_id, revision),
    FOREIGN KEY (student_id, task_id) REFERENCES learning_task(student_id, id) ON DELETE CASCADE
);
