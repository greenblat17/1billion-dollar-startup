-- Preserve the old recording-time history and the source of new speech-time facts.
ALTER TABLE onboarding_voices
    ADD COLUMN recording_duration_sec DOUBLE PRECISION,
    ADD COLUMN duration_method TEXT,
    ADD COLUMN duration_source TEXT,
    ADD COLUMN duration_fallback_reason TEXT;

ALTER TABLE habit_call_facts ADD COLUMN duration_source TEXT;
ALTER TABLE habit_call_summaries
    ADD COLUMN legacy_turns BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN vad_turns BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN fallback_turns BIGINT NOT NULL DEFAULT 0;

UPDATE habit_call_summaries SET legacy_turns = recognized_turns;

-- Runs after habit_call_event_trigger (trigger names sort alphabetically).
CREATE FUNCTION tag_habit_speech_time() RETURNS trigger AS $$
BEGIN
    IF NEW.kind != 'voice_processed' OR NEW.call_id IS NULL THEN
        RETURN NEW;
    END IF;
    UPDATE habit_call_facts SET duration_source = NEW.state
    WHERE call_id = NEW.call_id AND fact_key = 'voice_processed:' || COALESCE(NEW.message_id::text, md5(NEW.event_id));
    UPDATE habit_call_summaries SET
        legacy_turns = counts.legacy,
        vad_turns = counts.vad,
        fallback_turns = counts.fallback
    FROM (
        SELECT count(*) FILTER (WHERE seconds > 0 AND (duration_source IS NULL OR duration_source = 'recording_legacy')) AS legacy,
               count(*) FILTER (WHERE seconds > 0 AND (duration_source = 'vad' OR duration_source LIKE 'recording_fallback:%')) AS vad,
               count(*) FILTER (WHERE duration_source LIKE 'recording_fallback:%') AS fallback
        FROM habit_call_facts WHERE call_id = NEW.call_id AND kind = 'voice_processed'
    ) counts
    WHERE call_id = NEW.call_id;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER zz_tag_habit_speech_time AFTER INSERT OR UPDATE ON telegram_call_events
FOR EACH ROW EXECUTE FUNCTION tag_habit_speech_time();

CREATE OR REPLACE VIEW habit_call_daily AS
SELECT (COALESCE(opened_at, last_event_at) AT TIME ZONE 'Europe/Moscow')::date AS day,
       count(*) FILTER (WHERE closed_at IS NOT NULL AND recognized_turns > 0 AND complete) AS calls,
       COALESCE(sum(speech_seconds) FILTER (
           WHERE closed_at IS NOT NULL AND recognized_turns > 0 AND complete), 0) AS speech_seconds,
       count(*) FILTER (WHERE closed_at IS NULL OR recognized_turns = 0 OR NOT complete) AS excluded,
       count(*) FILTER (WHERE NOT complete) AS incomplete,
       count(*) FILTER (WHERE closed_at IS NOT NULL AND recognized_turns > 0 AND complete
                       AND legacy_turns > 0 AND vad_turns = 0) AS legacy_calls,
       count(*) FILTER (WHERE closed_at IS NOT NULL AND recognized_turns > 0 AND complete
                       AND vad_turns > 0 AND legacy_turns = 0) AS vad_calls,
       count(*) FILTER (WHERE closed_at IS NOT NULL AND recognized_turns > 0 AND complete
                       AND vad_turns > 0 AND legacy_turns > 0) AS mixed_calls,
       COALESCE(sum(fallback_turns) FILTER (
           WHERE closed_at IS NOT NULL AND recognized_turns > 0 AND complete), 0) AS fallback_turns
FROM habit_call_summaries
GROUP BY 1;
