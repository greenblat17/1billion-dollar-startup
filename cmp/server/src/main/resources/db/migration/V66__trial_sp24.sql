ALTER TABLE lesson ADD COLUMN trial_conversation_start_ms BIGINT;
ALTER TABLE lesson ADD COLUMN trial_reconnect_until TIMESTAMPTZ;
ALTER TABLE lesson ADD COLUMN trial_offer_sequence BIGINT;
ALTER TABLE lesson ADD COLUMN trial_regular_reaction TEXT
    CHECK (trial_regular_reaction IN ('yes','no','undecided'));
ALTER TABLE lesson ADD COLUMN trial_reaction_source_id TEXT;
