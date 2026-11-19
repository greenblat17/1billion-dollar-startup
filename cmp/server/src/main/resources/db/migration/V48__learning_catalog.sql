-- Shared curriculum only. No student records are read, migrated or backfilled.
CREATE TABLE learning_catalog_release (
    version TEXT PRIMARY KEY,
    content_digest TEXT NOT NULL,
    manifest JSONB NOT NULL,
    imported_at TIMESTAMPTZ NOT NULL,
    published_at TIMESTAMPTZ,
    reviewed_by TEXT,
    review_reference TEXT,
    CHECK ((published_at IS NULL AND reviewed_by IS NULL AND review_reference IS NULL) OR
           (published_at IS NOT NULL AND reviewed_by IS NOT NULL AND review_reference IS NOT NULL AND
            length(trim(reviewed_by)) > 0 AND length(trim(review_reference)) > 0))
);
CREATE TABLE learning_catalog_skill (
    version TEXT NOT NULL REFERENCES learning_catalog_release(version),
    id TEXT NOT NULL, content JSONB NOT NULL,
    PRIMARY KEY(version, id)
);
CREATE TABLE learning_catalog_problem (
    version TEXT NOT NULL, id TEXT NOT NULL, skill_id TEXT NOT NULL, content JSONB NOT NULL,
    PRIMARY KEY(version, id),
    FOREIGN KEY(version, skill_id) REFERENCES learning_catalog_skill(version, id)
);
CREATE TABLE learning_catalog_topic (
    version TEXT NOT NULL, id TEXT NOT NULL, skill_id TEXT NOT NULL,
    cefr TEXT NOT NULL CHECK (cefr IN ('A1','A2','B1','B2','C1')), content JSONB NOT NULL,
    PRIMARY KEY(version, id),
    FOREIGN KEY(version, skill_id) REFERENCES learning_catalog_skill(version, id)
);
CREATE INDEX learning_catalog_topic_axes ON learning_catalog_topic(version, skill_id, cefr);
CREATE TABLE learning_catalog_prerequisite (
    version TEXT NOT NULL, topic_id TEXT NOT NULL, prerequisite_id TEXT NOT NULL, rationale TEXT NOT NULL,
    PRIMARY KEY(version, topic_id, prerequisite_id),
    FOREIGN KEY(version, topic_id) REFERENCES learning_catalog_topic(version, id),
    FOREIGN KEY(version, prerequisite_id) REFERENCES learning_catalog_topic(version, id),
    CHECK (topic_id <> prerequisite_id)
);
CREATE TABLE learning_catalog_current (
    singleton BOOLEAN PRIMARY KEY DEFAULT TRUE CHECK (singleton),
    version TEXT NOT NULL REFERENCES learning_catalog_release(version)
);
