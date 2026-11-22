CREATE TABLE legacy_voice_cost (
    call_id TEXT PRIMARY KEY,
    provider TEXT NOT NULL,
    generation_id TEXT,
    input_digest TEXT NOT NULL,
    payload JSONB NOT NULL,
    revision BIGINT NOT NULL DEFAULT 1,
    outbox BOOLEAN NOT NULL DEFAULT TRUE,
    outbox_at TIMESTAMPTZ NOT NULL,
    due_at TIMESTAMPTZ,
    lease_until TIMESTAMPTZ,
    fence BIGINT NOT NULL DEFAULT 0,
    expires_at TIMESTAMPTZ NOT NULL
);
CREATE UNIQUE INDEX legacy_voice_cost_generation ON legacy_voice_cost(provider,generation_id) WHERE generation_id IS NOT NULL;
CREATE INDEX legacy_voice_cost_outbox ON legacy_voice_cost(outbox_at,call_id) WHERE outbox;
CREATE INDEX legacy_voice_cost_due ON legacy_voice_cost(due_at) WHERE due_at IS NOT NULL;
