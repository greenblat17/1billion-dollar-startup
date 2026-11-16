-- Task plans are independent of lesson_plan and of Observation/Score runtime.
CREATE TABLE learning_plan (
    id TEXT PRIMARY KEY,
    student_id TEXT NOT NULL,
    task_id TEXT NOT NULL UNIQUE,
    revision BIGINT NOT NULL CHECK (revision > 0),
    state_revision BIGINT NOT NULL CHECK (state_revision > 0),
    current_stage_id TEXT,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    FOREIGN KEY (student_id, task_id) REFERENCES learning_task(student_id, id) ON DELETE CASCADE
);
CREATE TABLE learning_plan_revision (
    plan_id TEXT NOT NULL REFERENCES learning_plan(id) ON DELETE CASCADE,
    revision BIGINT NOT NULL CHECK (revision > 0),
    definition JSONB NOT NULL CHECK (jsonb_typeof(definition) = 'object'),
    component TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (plan_id, revision)
);
ALTER TABLE learning_plan ADD CONSTRAINT learning_plan_current_revision
    FOREIGN KEY (id, revision) REFERENCES learning_plan_revision(plan_id, revision) DEFERRABLE INITIALLY DEFERRED;
CREATE TABLE learning_plan_history (
    plan_id TEXT NOT NULL REFERENCES learning_plan(id) ON DELETE CASCADE,
    state_revision BIGINT NOT NULL,
    plan_revision BIGINT NOT NULL,
    current_stage_id TEXT,
    action TEXT NOT NULL CHECK (action IN ('create','revise','select-stage')),
    component TEXT NOT NULL,
    reason TEXT NOT NULL CHECK (reason IN ('definition','plan_transition','student_request')),
    recorded_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (plan_id, state_revision),
    FOREIGN KEY (plan_id, plan_revision) REFERENCES learning_plan_revision(plan_id, revision) ON DELETE CASCADE
);
CREATE INDEX learning_plan_student ON learning_plan(student_id, id);
