CREATE TABLE soft_nudges (
    kind TEXT NOT NULL CHECK (kind IN ('onboarding', 'call')),
    flow_id TEXT NOT NULL,
    session_id TEXT NOT NULL,
    chat_id BIGINT NOT NULL,
    idle_since TIMESTAMPTZ NOT NULL,
    claimed_at TIMESTAMPTZ NOT NULL,
    sent_at TIMESTAMPTZ,
    status TEXT NOT NULL CHECK (status IN ('claimed', 'sent', 'failed', 'blocked', 'unknown')),
    returned_at TIMESTAMPTZ,
    PRIMARY KEY (kind, flow_id)
);

CREATE INDEX soft_nudges_claimed_idx ON soft_nudges (claimed_at DESC);
CREATE INDEX soft_nudges_return_idx ON soft_nudges (chat_id, sent_at)
    WHERE status = 'sent' AND returned_at IS NULL;

CREATE FUNCTION capture_soft_nudge_return() RETURNS trigger AS $$
BEGIN
    IF NEW.direction = 'incoming' AND NEW.kind = 'voice' AND NEW.status = 'received'
       AND NEW.chat_id IS NOT NULL THEN
        UPDATE soft_nudges SET returned_at = NEW.occurred_at
        WHERE chat_id = NEW.chat_id AND status = 'sent' AND returned_at IS NULL
          AND sent_at < NEW.occurred_at AND sent_at >= NEW.occurred_at - interval '24 hours'
          AND (
              (kind = 'onboarding' AND EXISTS (
                  SELECT 1 FROM onboarding_attempts a WHERE a.run_id = flow_id
                    AND (a.completed_at IS NULL OR a.completed_at >= NEW.occurred_at)))
              OR (kind = 'call' AND NOT EXISTS (
                  SELECT 1 FROM telegram_call_events e WHERE e.call_id::text = flow_id
                    AND e.kind = 'call_closed' AND e.occurred_at <= NEW.occurred_at))
          );
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;
CREATE TRIGGER soft_nudge_return AFTER INSERT ON interaction_events
FOR EACH ROW EXECUTE FUNCTION capture_soft_nudge_return();
