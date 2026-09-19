CREATE TABLE users (
    id TEXT PRIMARY KEY,
    email TEXT NOT NULL UNIQUE,
    password_hash TEXT NOT NULL,
    display_name TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE speaking_sessions (
    id TEXT PRIMARY KEY,
    user_id TEXT NOT NULL REFERENCES users (id),
    topic TEXT NOT NULL,
    tutor_voice TEXT NOT NULL,
    duration_sec INTEGER,
    openai_call_id TEXT,
    rtc_active BOOLEAN NOT NULL DEFAULT FALSE,
    status TEXT NOT NULL DEFAULT 'Created',
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE reviews (
    session_id TEXT PRIMARY KEY REFERENCES speaking_sessions (id),
    payload JSONB NOT NULL
);
