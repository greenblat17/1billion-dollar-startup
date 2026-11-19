-- SP-17A. Independent of the parallel call-event (V52) and Plan (V54) migrations.
CREATE TABLE learning_observation (
    id TEXT PRIMARY KEY,
    student_id TEXT NOT NULL REFERENCES student(id),
    anchor_event_id TEXT NOT NULL,
    attempt_id TEXT NOT NULL,
    evidence_slot TEXT NOT NULL,
    lesson_id TEXT,
    revision BIGINT NOT NULL CHECK (revision > 0),
    status TEXT NOT NULL CHECK (status IN ('active','invalidated')),
    document JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    UNIQUE(student_id,id),
    UNIQUE(student_id,anchor_event_id,evidence_slot),
    FOREIGN KEY(student_id,anchor_event_id) REFERENCES conversation_event(student_id,id),
    FOREIGN KEY(student_id,lesson_id) REFERENCES lesson(student_id,id)
);
CREATE UNIQUE INDEX learning_observation_problem_once ON learning_observation
    (student_id,attempt_id,(document->>'topicId'),(document->>'problemId'))
    WHERE status='active' AND document->>'topicId' IS NOT NULL AND document->>'problemId' IS NOT NULL;
CREATE INDEX learning_observation_student ON learning_observation(student_id,created_at,id);
CREATE TABLE learning_observation_revision (
    student_id TEXT NOT NULL,
    observation_id TEXT NOT NULL,
    revision BIGINT NOT NULL CHECK (revision > 0),
    status TEXT NOT NULL CHECK (status IN ('active','invalidated')),
    document JSONB NOT NULL,
    component TEXT NOT NULL,
    reason TEXT NOT NULL,
    recorded_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY(observation_id,revision),
    FOREIGN KEY(student_id,observation_id) REFERENCES learning_observation(student_id,id) ON DELETE CASCADE
);
CREATE TABLE learning_observation_source (
    student_id TEXT NOT NULL,
    observation_id TEXT NOT NULL,
    revision BIGINT NOT NULL,
    event_id TEXT NOT NULL,
    source_revision BIGINT NOT NULL,
    role TEXT NOT NULL CHECK (role IN ('user','assistant')),
    purpose TEXT NOT NULL CHECK (purpose IN ('answer','context','hint','correction')),
    span_start INTEGER,
    span_end INTEGER,
    CHECK ((span_start IS NULL AND span_end IS NULL) OR (span_start>=0 AND span_end>span_start)),
    PRIMARY KEY(observation_id,revision,event_id),
    FOREIGN KEY(observation_id,revision) REFERENCES learning_observation_revision(observation_id,revision) ON DELETE CASCADE,
    FOREIGN KEY(student_id,observation_id) REFERENCES learning_observation(student_id,id) ON DELETE CASCADE,
    FOREIGN KEY(student_id,event_id) REFERENCES conversation_event(student_id,id)
);
CREATE INDEX learning_observation_source_event ON learning_observation_source(student_id,event_id);
