ALTER TABLE memory_fact ADD COLUMN mention_policy TEXT NOT NULL DEFAULT 'natural'
    CHECK (mention_policy IN ('natural','context_only'));
ALTER TABLE memory_fact ADD COLUMN valid_until TIMESTAMPTZ;
CREATE TABLE memory_observation_review (
    student_id TEXT NOT NULL REFERENCES student(id),
    observation_key TEXT NOT NULL,
    status TEXT NOT NULL CHECK (status IN ('disputed','resolved')),
    explanation TEXT NOT NULL,
    source_event_id TEXT NOT NULL,
    revision BIGINT NOT NULL DEFAULT 1,
    PRIMARY KEY(student_id, observation_key),
    FOREIGN KEY(student_id, source_event_id) REFERENCES conversation_event(student_id,id)
);
