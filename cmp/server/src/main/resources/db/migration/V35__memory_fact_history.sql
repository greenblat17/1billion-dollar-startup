-- Confirmed fact revisions are immutable until an explicit privacy deletion.
CREATE TABLE memory_fact_history (
    student_id TEXT NOT NULL REFERENCES student(id), category TEXT NOT NULL,
    fact_key TEXT NOT NULL, revision BIGINT NOT NULL, value TEXT, kind TEXT NOT NULL,
    source_event_id TEXT, forgotten_at TIMESTAMPTZ, updated_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY(student_id, category, fact_key, revision),
    FOREIGN KEY(student_id, source_event_id) REFERENCES conversation_event(student_id, id)
);
INSERT INTO memory_fact_history SELECT student_id,category,fact_key,revision,value,kind,
    source_event_id,forgotten_at,updated_at FROM memory_fact;
CREATE FUNCTION retain_memory_fact_revision() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    INSERT INTO memory_fact_history VALUES (NEW.student_id,NEW.category,NEW.fact_key,NEW.revision,
        NEW.value,NEW.kind,NEW.source_event_id,NEW.forgotten_at,NEW.updated_at)
        ON CONFLICT DO NOTHING;
    RETURN NEW;
END;
$$;
CREATE TRIGGER memory_fact_revision AFTER INSERT OR UPDATE ON memory_fact
    FOR EACH ROW EXECUTE FUNCTION retain_memory_fact_revision();
