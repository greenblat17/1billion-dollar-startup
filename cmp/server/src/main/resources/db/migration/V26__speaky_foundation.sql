-- Canonical MVP data. Legacy app users/speaking_sessions remain independent.
CREATE TABLE tutor_identity (
    id TEXT NOT NULL, version BIGINT NOT NULL CHECK (version > 0), persona_ref TEXT NOT NULL,
    PRIMARY KEY (id, version)
);
INSERT INTO tutor_identity VALUES ('sasha', 1, 'kora_h');

CREATE TABLE student (
    id TEXT PRIMARY KEY, profile JSONB NOT NULL DEFAULT '{}', version BIGINT NOT NULL DEFAULT 1,
    data_epoch BIGINT NOT NULL DEFAULT 1, learning_revision BIGINT NOT NULL DEFAULT 0, deleted_at TIMESTAMPTZ,
    tutor_id TEXT NOT NULL DEFAULT 'sasha', tutor_version BIGINT NOT NULL DEFAULT 1,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    FOREIGN KEY (tutor_id, tutor_version) REFERENCES tutor_identity (id, version)
);
CREATE TABLE student_identity (
    provider TEXT NOT NULL, subject TEXT NOT NULL, student_id TEXT NOT NULL REFERENCES student(id),
    PRIMARY KEY (provider, subject)
);
CREATE TABLE learning_goal (
    student_id TEXT NOT NULL REFERENCES student(id), revision BIGINT NOT NULL,
    content JSONB NOT NULL, source_event_id TEXT, created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (student_id, revision)
);
CREATE TABLE tutor_conversation (
    id TEXT PRIMARY KEY, student_id TEXT NOT NULL REFERENCES student(id), channel TEXT NOT NULL,
    next_sequence BIGINT NOT NULL DEFAULT 1, UNIQUE (student_id, channel), UNIQUE (student_id, id)
);
CREATE TABLE conversation_event (
    id TEXT PRIMARY KEY, student_id TEXT NOT NULL REFERENCES student(id), conversation_id TEXT NOT NULL,
    sequence BIGINT NOT NULL, source_id TEXT NOT NULL, role TEXT NOT NULL CHECK (role IN ('user','assistant','system')),
    body TEXT, occurred_at TIMESTAMPTZ NOT NULL, received_at TIMESTAMPTZ NOT NULL, expires_at TIMESTAMPTZ NOT NULL,
    content_digest TEXT NOT NULL, metadata JSONB NOT NULL DEFAULT '{}',
    FOREIGN KEY (student_id, conversation_id) REFERENCES tutor_conversation(student_id, id),
    UNIQUE (student_id, id), UNIQUE (conversation_id, source_id), UNIQUE (conversation_id, sequence)
);
CREATE INDEX conversation_event_expiry ON conversation_event(expires_at) WHERE body IS NOT NULL;
CREATE TABLE tutor_inbox (
    student_id TEXT NOT NULL REFERENCES student(id), request_id TEXT NOT NULL,
    event_id TEXT NOT NULL, input_digest TEXT NOT NULL, status TEXT NOT NULL DEFAULT 'pending'
        CHECK (status IN ('pending','running','ready','failed')),
    response JSONB, expires_at TIMESTAMPTZ NOT NULL, lease_until TIMESTAMPTZ,
    fence BIGINT NOT NULL DEFAULT 0, created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (student_id, request_id),
    FOREIGN KEY (student_id, event_id) REFERENCES conversation_event(student_id, id)
);
CREATE TABLE execution_scope (
    token_digest TEXT PRIMARY KEY, student_id TEXT NOT NULL REFERENCES student(id),
    role TEXT NOT NULL CHECK (role IN ('text','voice','learning','operator')),
    request_id TEXT, lesson_id TEXT, job_id TEXT, fence BIGINT,
    data_epoch BIGINT NOT NULL, expires_at TIMESTAMPTZ NOT NULL
);
CREATE TABLE memory_fact (
    student_id TEXT NOT NULL REFERENCES student(id), category TEXT NOT NULL CHECK (category IN ('personal','learning')),
    fact_key TEXT NOT NULL, value TEXT, kind TEXT NOT NULL CHECK (kind IN ('user_report','self_assessment')),
    source_event_id TEXT, revision BIGINT NOT NULL DEFAULT 1, forgotten_at TIMESTAMPTZ,
    updated_at TIMESTAMPTZ NOT NULL, PRIMARY KEY(student_id, category, fact_key),
    FOREIGN KEY(student_id, source_event_id) REFERENCES conversation_event(student_id, id)
);
CREATE TABLE memory_candidate (
    id TEXT PRIMARY KEY, student_id TEXT NOT NULL REFERENCES student(id), fact_key TEXT NOT NULL,
    value TEXT, source_event_id TEXT NOT NULL, version BIGINT NOT NULL DEFAULT 1,
    status TEXT NOT NULL DEFAULT 'pending' CHECK (status IN ('pending','confirmed','rejected','expired')),
    expires_at TIMESTAMPTZ NOT NULL,
    FOREIGN KEY(student_id, source_event_id) REFERENCES conversation_event(student_id, id),
    UNIQUE(student_id, id), UNIQUE(student_id, source_event_id, fact_key)
);
CREATE TABLE schedule (
    id TEXT PRIMARY KEY, student_id TEXT NOT NULL REFERENCES student(id), version BIGINT NOT NULL DEFAULT 1,
    timezone TEXT NOT NULL, effective_from TIMESTAMPTZ NOT NULL, agreement JSONB NOT NULL,
    UNIQUE(student_id, id)
);
CREATE TABLE lesson (
    id TEXT PRIMARY KEY, student_id TEXT NOT NULL REFERENCES student(id), kind TEXT NOT NULL CHECK (kind IN ('trial','regular')),
    status TEXT NOT NULL DEFAULT 'scheduled' CHECK (status IN ('scheduled','in_progress','completed','missed','cancelled')),
    version BIGINT NOT NULL DEFAULT 1, starts_at TIMESTAMPTZ NOT NULL, duration_minutes INT NOT NULL CHECK (duration_minutes > 0),
    timezone TEXT NOT NULL, launch_mode TEXT NOT NULL CHECK (launch_mode IN ('placeholder','executable')),
    schedule_id TEXT, occurrence_key TEXT, previous_lesson_id TEXT, timing TEXT NOT NULL DEFAULT 'scheduled',
    transcript_revision BIGINT NOT NULL DEFAULT 0, execution_snapshot JSONB,
    ended_early BOOLEAN NOT NULL DEFAULT FALSE, outcome_reason TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(), updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE(student_id, id), UNIQUE(schedule_id, occurrence_key),
    FOREIGN KEY(student_id, schedule_id) REFERENCES schedule(student_id, id),
    FOREIGN KEY(student_id, previous_lesson_id) REFERENCES lesson(student_id, id),
    CHECK ((schedule_id IS NULL) = (occurrence_key IS NULL))
);
CREATE UNIQUE INDEX lesson_active_trial ON lesson(student_id) WHERE kind='trial' AND status IN ('scheduled','in_progress');
CREATE TABLE schedule_exception (
    student_id TEXT NOT NULL, schedule_id TEXT NOT NULL, occurrence_key TEXT NOT NULL,
    lesson_id TEXT NOT NULL, kind TEXT NOT NULL CHECK (kind IN ('moved','cancelled')),
    PRIMARY KEY(schedule_id, occurrence_key),
    FOREIGN KEY(student_id, schedule_id) REFERENCES schedule(student_id, id),
    FOREIGN KEY(student_id, lesson_id) REFERENCES lesson(student_id, id)
);
CREATE TABLE call_session (
    id TEXT PRIMARY KEY, student_id TEXT NOT NULL, lesson_id TEXT NOT NULL, attempt_id TEXT NOT NULL,
    status TEXT NOT NULL CHECK (status IN ('connected','disconnected','ended')),
    started_at TIMESTAMPTZ NOT NULL, ended_at TIMESTAMPTZ, end_reason TEXT, provider_ref TEXT,
    FOREIGN KEY(student_id, lesson_id) REFERENCES lesson(student_id, id),
    UNIQUE(lesson_id, attempt_id), UNIQUE(student_id, id), UNIQUE(student_id, lesson_id, id)
);
CREATE UNIQUE INDEX call_session_active ON call_session(lesson_id) WHERE status='connected';
CREATE TABLE lesson_event (
    student_id TEXT NOT NULL, lesson_id TEXT NOT NULL, call_session_id TEXT NOT NULL,
    event_id TEXT NOT NULL, source_id TEXT NOT NULL, sequence BIGINT NOT NULL,
    FOREIGN KEY(student_id, lesson_id, call_session_id) REFERENCES call_session(student_id, lesson_id, id),
    FOREIGN KEY(student_id, event_id) REFERENCES conversation_event(student_id, id),
    PRIMARY KEY(call_session_id, source_id), UNIQUE(lesson_id, sequence)
);
CREATE TABLE subscription (
    id TEXT PRIMARY KEY, student_id TEXT NOT NULL REFERENCES student(id), status TEXT NOT NULL,
    version BIGINT NOT NULL DEFAULT 1, UNIQUE(student_id, id)
);
CREATE TABLE payment_receipt (
    provider TEXT NOT NULL, external_id TEXT NOT NULL, student_id TEXT NOT NULL, subscription_id TEXT NOT NULL,
    input_digest TEXT NOT NULL, confirmed_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY(provider, external_id),
    FOREIGN KEY(student_id, subscription_id) REFERENCES subscription(student_id, id)
);
CREATE TABLE entitlement (
    id TEXT PRIMARY KEY, student_id TEXT NOT NULL, subscription_id TEXT NOT NULL,
    valid_from TIMESTAMPTZ NOT NULL, valid_until TIMESTAMPTZ NOT NULL CHECK (valid_until > valid_from),
    source_provider TEXT NOT NULL, source_external_id TEXT NOT NULL,
    FOREIGN KEY(student_id, subscription_id) REFERENCES subscription(student_id, id),
    FOREIGN KEY(source_provider, source_external_id) REFERENCES payment_receipt(provider, external_id),
    UNIQUE(source_provider, source_external_id)
);
CREATE TABLE roadmap (
    id TEXT PRIMARY KEY, student_id TEXT NOT NULL UNIQUE REFERENCES student(id), active_revision BIGINT,
    UNIQUE(student_id, id)
);
CREATE TABLE roadmap_revision (
    student_id TEXT NOT NULL, roadmap_id TEXT NOT NULL, revision BIGINT NOT NULL, goal_revision BIGINT NOT NULL,
    content JSONB NOT NULL, reason TEXT NOT NULL, confirmed_source_id TEXT,
    status TEXT NOT NULL CHECK (status IN ('proposed','active','superseded')),
    PRIMARY KEY(roadmap_id, revision),
    FOREIGN KEY(student_id, roadmap_id) REFERENCES roadmap(student_id, id),
    FOREIGN KEY(student_id, goal_revision) REFERENCES learning_goal(student_id, revision)
);
CREATE TABLE roadmap_step (
    roadmap_id TEXT NOT NULL, roadmap_revision BIGINT NOT NULL, step_id TEXT NOT NULL,
    position INT NOT NULL, outcome TEXT NOT NULL, status TEXT NOT NULL DEFAULT 'planned',
    PRIMARY KEY(roadmap_id, roadmap_revision, step_id),
    FOREIGN KEY(roadmap_id, roadmap_revision) REFERENCES roadmap_revision(roadmap_id, revision)
);
CREATE TABLE lesson_evaluation (
    student_id TEXT NOT NULL, lesson_id TEXT NOT NULL, source_revision BIGINT NOT NULL, analyzer_version TEXT NOT NULL,
    result JSONB NOT NULL, created_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY(lesson_id, source_revision, analyzer_version),
    FOREIGN KEY(student_id, lesson_id) REFERENCES lesson(student_id, id)
);
CREATE TABLE lesson_plan (
    student_id TEXT NOT NULL, lesson_id TEXT NOT NULL, revision BIGINT NOT NULL,
    goal_revision BIGINT NOT NULL, learning_revision BIGINT NOT NULL, roadmap_revision BIGINT, evaluation_refs JSONB NOT NULL DEFAULT '[]',
    status TEXT NOT NULL CHECK (status IN ('ready','stale')), content JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY(lesson_id, revision), FOREIGN KEY(student_id, lesson_id) REFERENCES lesson(student_id, id)
);
CREATE TABLE memory_observation (
    id TEXT PRIMARY KEY, student_id TEXT NOT NULL, lesson_id TEXT NOT NULL,
    source_revision BIGINT NOT NULL, analyzer_version TEXT NOT NULL, observation_key TEXT NOT NULL,
    dimension TEXT NOT NULL CHECK (dimension IN ('fluency','confidence','autonomy','grammar','vocabulary')),
    content TEXT NOT NULL, uncertainty TEXT NOT NULL, source_event_id TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    FOREIGN KEY(student_id, lesson_id) REFERENCES lesson(student_id, id),
    FOREIGN KEY(student_id, source_event_id) REFERENCES conversation_event(student_id, id),
    UNIQUE(lesson_id, source_revision, analyzer_version, observation_key)
);
CREATE TABLE foundation_operation (
    student_id TEXT NOT NULL REFERENCES student(id), operation_id TEXT NOT NULL,
    action TEXT NOT NULL, request_id TEXT, role TEXT NOT NULL, input_digest TEXT NOT NULL, result JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL, expires_at TIMESTAMPTZ,
    PRIMARY KEY(student_id, operation_id)
);
CREATE TABLE foundation_job (
    id TEXT PRIMARY KEY, student_id TEXT NOT NULL REFERENCES student(id), data_epoch BIGINT NOT NULL,
    kind TEXT NOT NULL, schema_version INT NOT NULL DEFAULT 1 CHECK (schema_version=1), dedup_key TEXT NOT NULL,
    target_id TEXT, input_refs JSONB NOT NULL, status TEXT NOT NULL DEFAULT 'queued'
        CHECK (status IN ('queued','running','succeeded','retry_wait','failed','superseded','cancelled')),
    due_at TIMESTAMPTZ NOT NULL, deadline TIMESTAMPTZ NOT NULL,
    lease_until TIMESTAMPTZ, fence BIGINT NOT NULL DEFAULT 0, attempts INT NOT NULL DEFAULT 0,
    max_attempts INT NOT NULL DEFAULT 3 CHECK (max_attempts > 0), error_code TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(), UNIQUE(student_id, kind, dedup_key)
);
CREATE INDEX foundation_job_due ON foundation_job(due_at) WHERE status IN ('queued','retry_wait','running');
CREATE TABLE foundation_job_attempt (
    job_id TEXT NOT NULL REFERENCES foundation_job(id), fence BIGINT NOT NULL, started_at TIMESTAMPTZ NOT NULL,
    finished_at TIMESTAMPTZ, outcome TEXT, PRIMARY KEY(job_id, fence)
);
CREATE TABLE tutor_delivery (
    student_id TEXT NOT NULL, request_id TEXT NOT NULL, kind TEXT NOT NULL,
    outcome TEXT NOT NULL CHECK (outcome IN ('sent','unknown','failed')), message_id BIGINT,
    lesson_id TEXT, lesson_version BIGINT,
    PRIMARY KEY(student_id, request_id, kind),
    FOREIGN KEY(student_id, request_id) REFERENCES tutor_inbox(student_id, request_id),
    FOREIGN KEY(student_id, lesson_id) REFERENCES lesson(student_id, id)
);
CREATE TABLE foundation_import (
    source_id TEXT PRIMARY KEY, content_digest TEXT NOT NULL, target_refs JSONB NOT NULL,
    imported_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE tutor_summary (student_id TEXT PRIMARY KEY REFERENCES student(id), body TEXT NOT NULL, expires_at TIMESTAMPTZ NOT NULL);
