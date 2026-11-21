-- SP-18. A separate evidence score for problems outside active Learning Tasks.
CREATE TABLE learning_problem_score (
    student_id TEXT NOT NULL REFERENCES student(id) ON DELETE CASCADE,
    topic_id TEXT NOT NULL,
    problem_id TEXT NOT NULL,
    score NUMERIC NOT NULL CHECK (score >= 0 AND score <= 100),
    evidence_count INTEGER NOT NULL CHECK (evidence_count >= 0),
    updated_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (student_id, topic_id, problem_id)
);
