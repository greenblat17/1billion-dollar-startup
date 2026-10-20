ALTER TABLE onboarding_nudges
    ADD COLUMN action TEXT,
    ADD COLUMN template_version TEXT,
    ADD COLUMN attempt_number INTEGER,
    ADD COLUMN inactive_seconds BIGINT,
    ADD COLUMN status TEXT NOT NULL DEFAULT 'unknown'
        CHECK (status IN ('unknown', 'sent', 'blocked', 'failed')),
    ADD COLUMN sent_at TIMESTAMPTZ,
    ADD COLUMN message_id BIGINT,
    ADD COLUMN outcome_at TIMESTAMPTZ;

CREATE INDEX onboarding_nudges_sent_idx ON onboarding_nudges (session_id, sent_at) WHERE status = 'sent';
CREATE INDEX onboarding_voices_session_received_idx ON onboarding_voices (session_id, received_at);
CREATE INDEX onboarding_attempts_chat_idx ON onboarding_attempts (telegram_chat_id);

-- Keep only timestamps and event IDs, independently of the audit content retention.
-- Capture after claim, including inbound updates racing the Telegram acknowledgement.
CREATE TABLE onboarding_nudge_interactions (
    session_id TEXT NOT NULL,
    event_id TEXT NOT NULL,
    kind TEXT NOT NULL DEFAULT 'incoming',
    occurred_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (session_id, event_id)
);
CREATE INDEX onboarding_nudge_interactions_time_idx ON onboarding_nudge_interactions (session_id, occurred_at);
CREATE FUNCTION capture_onboarding_nudge_interaction() RETURNS trigger AS $$
BEGIN
    IF NEW.direction = 'incoming' AND NEW.status = 'received'
       AND NEW.kind IN ('callback', 'voice', 'text', 'photo') THEN
        INSERT INTO onboarding_nudge_interactions (session_id, event_id, occurred_at)
        SELECT DISTINCT a.session_id, NEW.event_id, NEW.occurred_at
        FROM onboarding_attempts a
        WHERE a.telegram_chat_id = NEW.chat_id AND EXISTS (
            SELECT 1 FROM onboarding_nudges n WHERE n.session_id = a.session_id
              AND n.claimed_at <= NEW.occurred_at
              AND COALESCE(n.sent_at, n.claimed_at) > NEW.occurred_at - interval '7 days'
        )
        ON CONFLICT DO NOTHING;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;
CREATE TRIGGER onboarding_nudge_incoming AFTER INSERT ON interaction_events
FOR EACH ROW EXECUTE FUNCTION capture_onboarding_nudge_interaction();
