-- Student-owned learning data. No legacy backfill and no automatic assessment.
CREATE TABLE student_learning_state (
    student_id TEXT PRIMARY KEY REFERENCES student(id),
    revision BIGINT NOT NULL CHECK (revision > 0),
    profile JSONB NOT NULL CHECK (jsonb_typeof(profile) = 'object'),
    provenance JSONB NOT NULL CHECK (jsonb_typeof(provenance) = 'object'),
    updated_at TIMESTAMPTZ NOT NULL
);
CREATE TABLE student_topic_knowledge (
    student_id TEXT NOT NULL REFERENCES student(id),
    catalog_version TEXT NOT NULL,
    topic_id TEXT NOT NULL,
    revision BIGINT NOT NULL CHECK (revision > 0),
    status TEXT NOT NULL CHECK (status IN ('Known','Not Known','Presumed Not Known','Unknown')),
    explanation TEXT NOT NULL,
    sources JSONB NOT NULL CHECK (jsonb_typeof(sources) = 'array'),
    component TEXT NOT NULL,
    assessed_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (student_id, catalog_version, topic_id, revision),
    FOREIGN KEY (catalog_version, topic_id) REFERENCES learning_catalog_topic(version, id)
);
