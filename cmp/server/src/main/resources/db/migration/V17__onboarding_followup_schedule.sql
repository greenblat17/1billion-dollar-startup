CREATE TABLE onboarding_followup_schedule (
    session_id TEXT PRIMARY KEY,
    chat_id BIGINT NOT NULL,
    last_inbound_at TIMESTAMPTZ NOT NULL,
    preferred_utc_minute SMALLINT,
    next_due_at TIMESTAMPTZ,
    visit_count INTEGER NOT NULL DEFAULT 0,
    dirty BOOLEAN NOT NULL DEFAULT TRUE,
    first_practice_at TIMESTAMPTZ,
    paused_stage TEXT,
    stopped_at TIMESTAMPTZ,
    stop_reason TEXT CHECK (stop_reason IN ('user', 'blocked')),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CHECK (preferred_utc_minute BETWEEN 0 AND 1439),
    CHECK (visit_count >= 0)
);
CREATE INDEX onboarding_followup_due_idx ON onboarding_followup_schedule (next_due_at)
    WHERE dirty = FALSE AND stopped_at IS NULL;
CREATE INDEX onboarding_followup_dirty_idx ON onboarding_followup_schedule (updated_at)
    WHERE dirty = TRUE AND stopped_at IS NULL AND first_practice_at IS NULL;

ALTER TABLE onboarding_nudges
    ADD COLUMN scheduled_for TIMESTAMPTZ,
    ADD COLUMN visit_count INTEGER,
    ADD COLUMN timing_policy TEXT,
    ADD COLUMN journey_stage TEXT,
    ADD COLUMN copy_id TEXT,
    ADD COLUMN catalog_version TEXT,
    ADD COLUMN final_message BOOLEAN NOT NULL DEFAULT FALSE;
CREATE INDEX onboarding_nudges_stage_copy_idx ON onboarding_nudges (session_id, journey_stage, copy_id)
    WHERE copy_id IS NOT NULL;

CREATE FUNCTION init_onboarding_followup_schedule() RETURNS trigger AS $$
BEGIN
    IF NEW.telegram_chat_id IS NOT NULL AND NEW.session_id LIKE 'tg-%' THEN
        INSERT INTO onboarding_followup_schedule (session_id, chat_id, last_inbound_at)
        VALUES (NEW.session_id, NEW.telegram_chat_id, NEW.started_at)
        ON CONFLICT (session_id) DO UPDATE SET
            chat_id = EXCLUDED.chat_id,
            last_inbound_at = GREATEST(onboarding_followup_schedule.last_inbound_at, EXCLUDED.last_inbound_at),
            dirty = TRUE,
            updated_at = now();
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;
CREATE TRIGGER onboarding_followup_attempt AFTER INSERT ON onboarding_attempts
FOR EACH ROW EXECUTE FUNCTION init_onboarding_followup_schedule();

CREATE FUNCTION wake_onboarding_followup_after_stage_change() RETURNS trigger AS $$
BEGIN
    IF NEW.session_id LIKE 'tg-%' THEN
        UPDATE onboarding_followup_schedule SET dirty = TRUE, updated_at = now()
        WHERE session_id = NEW.session_id AND paused_stage IS NOT NULL AND stopped_at IS NULL;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;
CREATE TRIGGER onboarding_followup_stage_change AFTER UPDATE ON onboarding_attempts
FOR EACH ROW EXECUTE FUNCTION wake_onboarding_followup_after_stage_change();

CREATE FUNCTION capture_onboarding_followup_activity() RETURNS trigger AS $$
BEGIN
    IF NEW.direction = 'incoming' AND NEW.status = 'received'
       AND NEW.kind IN ('callback', 'voice', 'text', 'photo') AND NEW.chat_id IS NOT NULL THEN
        INSERT INTO onboarding_followup_schedule (session_id, chat_id, last_inbound_at)
        SELECT DISTINCT a.session_id, NEW.chat_id, NEW.occurred_at
        FROM onboarding_attempts a
        WHERE a.telegram_chat_id = NEW.chat_id AND a.session_id LIKE 'tg-%'
        ON CONFLICT (session_id) DO UPDATE SET
            last_inbound_at = GREATEST(onboarding_followup_schedule.last_inbound_at, EXCLUDED.last_inbound_at),
            dirty = TRUE,
            updated_at = now();
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;
CREATE TRIGGER onboarding_followup_incoming AFTER INSERT ON interaction_events
FOR EACH ROW EXECUTE FUNCTION capture_onboarding_followup_activity();

INSERT INTO onboarding_followup_schedule (session_id, chat_id, last_inbound_at)
SELECT DISTINCT ON (a.session_id) a.session_id, a.telegram_chat_id,
       GREATEST(a.started_at, COALESCE(c.last_incoming_at, a.started_at))
FROM onboarding_attempts a
LEFT JOIN interaction_chats c ON c.chat_id = a.telegram_chat_id
WHERE a.session_id LIKE 'tg-%' AND a.telegram_chat_id IS NOT NULL
ORDER BY a.session_id, a.started_at DESC, a.attempt_number DESC
ON CONFLICT (session_id) DO NOTHING;
