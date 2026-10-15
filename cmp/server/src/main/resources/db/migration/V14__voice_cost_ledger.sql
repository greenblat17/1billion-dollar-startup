CREATE TABLE voice_cost_calls (
    call_id UUID PRIMARY KEY,
    attempt_id UUID NOT NULL,
    component TEXT NOT NULL CHECK (component IN ('stt', 'llm', 'tts')),
    provider TEXT NOT NULL,
    model TEXT NOT NULL,
    result TEXT NOT NULL CHECK (result IN ('success', 'error', 'cancelled')),
    occurred_at TIMESTAMPTZ NOT NULL,
    cost_micro_usd BIGINT CHECK (cost_micro_usd >= 0),
    price_status TEXT NOT NULL CHECK (price_status IN ('pending', 'known', 'unavailable')),
    generation_id TEXT,
    revision INTEGER NOT NULL CHECK (revision > 0),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CHECK ((price_status = 'known') = (cost_micro_usd IS NOT NULL))
);

CREATE INDEX voice_cost_calls_attempt_idx ON voice_cost_calls (attempt_id);
CREATE INDEX voice_cost_calls_occurred_idx ON voice_cost_calls (occurred_at);

CREATE TABLE voice_cost_exchanges (
    attempt_id UUID PRIMARY KEY,
    delivered_at TIMESTAMPTZ NOT NULL,
    expected_stt INTEGER CHECK (expected_stt >= 0),
    expected_llm INTEGER CHECK (expected_llm >= 0),
    expected_tts INTEGER CHECK (expected_tts >= 0)
);

CREATE INDEX voice_cost_exchanges_delivered_idx ON voice_cost_exchanges (delivered_at);
