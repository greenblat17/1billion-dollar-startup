CREATE TABLE initial_roadmap_delivery (
    id TEXT PRIMARY KEY,
    student_id TEXT NOT NULL REFERENCES student(id),
    lesson_id TEXT NOT NULL,
    roadmap_revision BIGINT NOT NULL,
    data_epoch BIGINT NOT NULL,
    status TEXT NOT NULL CHECK(status IN ('pending','generating','ready','unknown','sent','cancelled')),
    body TEXT,
    next_part INTEGER NOT NULL DEFAULT 0,
    attempts INTEGER NOT NULL DEFAULT 0,
    lease_until TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL,
    UNIQUE(student_id,roadmap_revision)
);
CREATE INDEX initial_roadmap_delivery_queue ON initial_roadmap_delivery(status,created_at);
