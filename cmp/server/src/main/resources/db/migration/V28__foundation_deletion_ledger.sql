-- Export this ledger separately before restoring an older database backup.
-- It contains identifiers and deletion boundaries, never deleted content.
CREATE TABLE foundation_deletion (
    id TEXT PRIMARY KEY,
    student_id TEXT NOT NULL,
    kind TEXT NOT NULL CHECK (kind IN ('forget','delete_student')),
    category TEXT,
    fact_key TEXT,
    deleted_at TIMESTAMPTZ NOT NULL,
    resulting_epoch BIGINT NOT NULL,
    CHECK ((kind='forget') = (category IS NOT NULL AND fact_key IS NOT NULL))
);
CREATE INDEX foundation_deletion_student ON foundation_deletion(student_id,resulting_epoch);
