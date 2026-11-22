CREATE TABLE legacy_call_owner (session_id TEXT PRIMARY KEY);
CREATE TABLE legacy_call (
    id TEXT PRIMARY KEY,
    session_id TEXT NOT NULL REFERENCES legacy_call_owner(session_id),
    day DATE NOT NULL,
    status TEXT NOT NULL CHECK (status IN ('open','closed')),
    payload JSONB NOT NULL
);
CREATE UNIQUE INDEX legacy_call_one_open ON legacy_call(session_id) WHERE status='open';
CREATE INDEX legacy_call_recent ON legacy_call(session_id,day,id);
CREATE TABLE legacy_call_day (
    session_id TEXT NOT NULL REFERENCES legacy_call_owner(session_id),
    day DATE NOT NULL,
    goal_noted BOOLEAN NOT NULL DEFAULT FALSE,
    duration_method TEXT,
    PRIMARY KEY(session_id,day)
);
