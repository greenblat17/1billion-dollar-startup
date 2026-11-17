-- SP-19B: current Observation revisions drive real score state.
ALTER TABLE learning_score_stage ADD COLUMN score_source TEXT NOT NULL DEFAULT 'fixture'
    CHECK (score_source IN ('fixture','observation'));
ALTER TABLE learning_score_event ADD COLUMN observation_id TEXT;
ALTER TABLE learning_score_event ADD COLUMN observation_revision BIGINT;
ALTER TABLE learning_score_event ADD CONSTRAINT score_observation_pair CHECK
    ((observation_id IS NULL AND observation_revision IS NULL) OR
     (observation_id IS NOT NULL AND observation_revision > 0));
CREATE UNIQUE INDEX learning_score_event_observation ON learning_score_event(student_id,observation_id)
    WHERE observation_id IS NOT NULL;
