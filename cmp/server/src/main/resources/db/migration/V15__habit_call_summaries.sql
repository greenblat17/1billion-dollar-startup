-- Compact facts retain only the fields needed for idempotence and duration.
-- They deliberately contain no Telegram identity, audio or transcript.
CREATE TABLE habit_call_summaries (
    call_id CHAR(32) PRIMARY KEY,
    opened_at TIMESTAMPTZ,
    closed_at TIMESTAMPTZ,
    last_event_at TIMESTAMPTZ NOT NULL,
    recognized_turns BIGINT NOT NULL DEFAULT 0,
    speech_seconds DOUBLE PRECISION NOT NULL DEFAULT 0,
    complete BOOLEAN NOT NULL DEFAULT false
);
CREATE INDEX habit_call_opened_idx ON habit_call_summaries (opened_at);
CREATE INDEX habit_call_day_idx ON habit_call_summaries (((COALESCE(opened_at, last_event_at) AT TIME ZONE 'Europe/Moscow')::date));
CREATE INDEX habit_call_expiry_idx ON habit_call_summaries (last_event_at);
CREATE TABLE habit_call_facts (
    call_id CHAR(32) NOT NULL REFERENCES habit_call_summaries ON DELETE CASCADE,
    fact_key TEXT NOT NULL,
    kind TEXT NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL,
    seconds DOUBLE PRECISION,
    PRIMARY KEY (call_id, fact_key)
);

CREATE FUNCTION record_habit_call_fact(cid CHAR(32), eid TEXT, k TEXT, at_time TIMESTAMPTZ,
                                      mid BIGINT, secs DOUBLE PRECISION) RETURNS void AS $$
BEGIN
    IF cid IS NULL OR k NOT IN ('call_open', 'call_closed', 'voice_received', 'voice_processed') THEN
        RETURN;
    END IF;
    -- Serialize changes for one call, including late arrivals and backfill.
    INSERT INTO habit_call_summaries(call_id, last_event_at) VALUES (cid, at_time)
    ON CONFLICT (call_id) DO UPDATE SET
        last_event_at = GREATEST(habit_call_summaries.last_event_at, EXCLUDED.last_event_at);
    INSERT INTO habit_call_facts(call_id, fact_key, kind, occurred_at, seconds)
    VALUES (cid, k || ':' || CASE WHEN k IN ('call_open', 'call_closed') THEN 'call'
                                 ELSE COALESCE(mid::text, md5(eid)) END, k, at_time, secs)
    ON CONFLICT (call_id, fact_key) DO UPDATE SET
        seconds = COALESCE(habit_call_facts.seconds, EXCLUDED.seconds),
        occurred_at = LEAST(habit_call_facts.occurred_at, EXCLUDED.occurred_at);
    UPDATE habit_call_summaries SET
        opened_at = f.opened_at, closed_at = f.closed_at, last_event_at = f.last_at,
        recognized_turns = f.recognized, speech_seconds = f.speech,
        complete = f.opened_at IS NOT NULL AND f.unknown_seconds = 0 AND f.received = f.processed
          AND NOT EXISTS (
              SELECT 1 FROM habit_call_facts incoming
              WHERE incoming.call_id = cid AND incoming.kind = 'voice_received'
                AND NOT EXISTS (SELECT 1 FROM habit_call_facts processed
                    WHERE processed.call_id = cid
                      AND processed.fact_key = replace(incoming.fact_key, 'voice_received:', 'voice_processed:'))
          )
    FROM (
        SELECT min(occurred_at) FILTER (WHERE kind = 'call_open') AS opened_at,
               max(occurred_at) FILTER (WHERE kind = 'call_closed') AS closed_at,
               max(occurred_at) AS last_at,
               count(*) FILTER (WHERE kind = 'voice_processed' AND seconds > 0) AS recognized,
               COALESCE(sum(seconds) FILTER (WHERE kind = 'voice_processed'), 0) AS speech,
               count(*) FILTER (WHERE kind = 'voice_processed' AND seconds IS NULL) AS unknown_seconds,
               count(*) FILTER (WHERE kind = 'voice_received') AS received,
               count(*) FILTER (WHERE kind = 'voice_processed') AS processed
        FROM habit_call_facts WHERE call_id = cid
    ) f WHERE call_id = cid;
END;
$$ LANGUAGE plpgsql;

CREATE FUNCTION habit_call_event_trigger() RETURNS trigger AS $$
BEGIN
    PERFORM record_habit_call_fact(NEW.call_id, NEW.event_id, NEW.kind,
                                  NEW.occurred_at, NEW.message_id, NEW.seconds);
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;
CREATE TRIGGER habit_call_event AFTER INSERT OR UPDATE ON telegram_call_events
FOR EACH ROW EXECUTE FUNCTION habit_call_event_trigger();

-- Keyset batches avoid a capped UI snapshot. Migration is atomic and retryable.
DO $$
DECLARE last_id TEXT := ''; item RECORD; batch_size INTEGER;
BEGIN
    LOOP
        batch_size := 0;
        FOR item IN SELECT * FROM telegram_call_events
            WHERE event_id > last_id AND call_id IS NOT NULL
              AND kind IN ('call_open', 'call_closed', 'voice_received', 'voice_processed')
            ORDER BY event_id LIMIT 500
        LOOP
            PERFORM record_habit_call_fact(item.call_id, item.event_id, item.kind,
                                          item.occurred_at, item.message_id, item.seconds);
            last_id := item.event_id;
            batch_size := batch_size + 1;
        END LOOP;
        EXIT WHEN batch_size = 0;
    END LOOP;
END;
$$;

CREATE VIEW habit_call_daily AS
SELECT (COALESCE(opened_at, last_event_at) AT TIME ZONE 'Europe/Moscow')::date AS day,
       count(*) FILTER (WHERE closed_at IS NOT NULL AND recognized_turns > 0 AND complete) AS calls,
       COALESCE(sum(speech_seconds) FILTER (
           WHERE closed_at IS NOT NULL AND recognized_turns > 0 AND complete), 0) AS speech_seconds,
       count(*) FILTER (WHERE closed_at IS NULL OR recognized_turns = 0 OR NOT complete) AS excluded,
       count(*) FILTER (WHERE NOT complete) AS incomplete
FROM habit_call_summaries
GROUP BY 1;
