-- A DST gap/fold is an unresolved agreed occurrence, never a guessed UTC Lesson.
CREATE TABLE schedule_time_issue (
    student_id TEXT NOT NULL,
    schedule_id TEXT NOT NULL,
    occurrence_key TEXT NOT NULL,
    local_date TEXT NOT NULL,
    local_time TEXT NOT NULL,
    timezone TEXT NOT NULL,
    schedule_version BIGINT NOT NULL,
    data_epoch BIGINT NOT NULL,
    status TEXT NOT NULL DEFAULT 'pending' CHECK(status IN ('pending','resolved','cancelled')),
    delivery TEXT NOT NULL DEFAULT 'pending' CHECK(delivery IN ('pending','unknown','sent','cancelled')),
    PRIMARY KEY(schedule_id,occurrence_key),
    FOREIGN KEY(student_id,schedule_id) REFERENCES schedule(student_id,id)
);
ALTER TABLE trial_notice ADD COLUMN lesson_version BIGINT;
