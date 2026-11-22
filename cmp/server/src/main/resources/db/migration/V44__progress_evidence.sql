ALTER TABLE memory_observation ADD COLUMN evidence JSONB;
CREATE TABLE progress_review (
    student_id TEXT NOT NULL REFERENCES student(id),
    job_id TEXT NOT NULL REFERENCES foundation_job(id),
    goal_revision BIGINT NOT NULL,
    roadmap_revision BIGINT NOT NULL,
    learning_revision BIGINT NOT NULL,
    result JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY(student_id, job_id),
    FOREIGN KEY(student_id, goal_revision) REFERENCES learning_goal(student_id, revision)
);
CREATE INDEX progress_review_student_goal ON progress_review(student_id, goal_revision, created_at DESC);
CREATE INDEX progress_review_job ON progress_review(job_id);
