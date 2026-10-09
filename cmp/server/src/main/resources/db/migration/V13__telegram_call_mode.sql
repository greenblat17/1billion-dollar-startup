ALTER TABLE telegram_call_events
    ADD COLUMN scenario_kind TEXT
    CONSTRAINT telegram_call_events_scenario_kind_check
    CHECK (scenario_kind IS NULL OR scenario_kind IN ('free', 'job', 'manager', 'custom'));
