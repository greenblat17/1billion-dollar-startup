-- SP-19A: fixture-driven score state. No Observation consumer is installed here.
CREATE TABLE learning_score_stage (
    student_id TEXT NOT NULL,
    task_id TEXT NOT NULL,
    plan_id TEXT NOT NULL,
    plan_revision BIGINT NOT NULL,
    stage_id TEXT NOT NULL,
    stage_attempt_id TEXT NOT NULL,
    attempt_number BIGINT NOT NULL DEFAULT 1 CHECK (attempt_number > 0),
    kind TEXT NOT NULL CHECK (kind IN ('numeric','binary')),
    score NUMERIC CHECK (score >= 0 AND score <= 100),
    completed BOOLEAN NOT NULL DEFAULT FALSE,
    state_revision BIGINT NOT NULL DEFAULT 1 CHECK (state_revision > 0),
    updated_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (student_id,task_id,plan_id,plan_revision,stage_id),
    FOREIGN KEY (student_id,task_id) REFERENCES learning_task(student_id,id) ON DELETE CASCADE,
    FOREIGN KEY (plan_id,plan_revision) REFERENCES learning_plan_revision(plan_id,revision) ON DELETE CASCADE,
    CHECK ((kind='numeric' AND score IS NOT NULL) OR (kind='binary' AND score IS NULL))
);
CREATE TABLE learning_score_event (
    student_id TEXT NOT NULL,
    task_id TEXT NOT NULL,
    plan_id TEXT NOT NULL,
    plan_revision BIGINT NOT NULL,
    stage_id TEXT NOT NULL,
    fixture_event_id TEXT NOT NULL,
    stage_attempt_id TEXT NOT NULL,
    attempt_number BIGINT NOT NULL,
    event_kind TEXT NOT NULL CHECK (event_kind IN ('outcome','binary','reopen')),
    outcome TEXT,
    reason TEXT,
    score_before NUMERIC,
    score_after NUMERIC,
    completed_after BOOLEAN NOT NULL,
    recorded_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (student_id,task_id,plan_id,plan_revision,stage_id,fixture_event_id),
    FOREIGN KEY (student_id,task_id,plan_id,plan_revision,stage_id)
        REFERENCES learning_score_stage(student_id,task_id,plan_id,plan_revision,stage_id) ON DELETE CASCADE
);
CREATE INDEX learning_score_event_task ON learning_score_event(student_id,task_id,recorded_at);
