ALTER TABLE foundation_job ADD COLUMN source_job_id TEXT;

ALTER TABLE learning_roadmap ADD COLUMN generation_job_id TEXT, ADD COLUMN generation_fence BIGINT;
ALTER TABLE learning_roadmap ADD COLUMN legacy_generation_source BOOLEAN NOT NULL DEFAULT TRUE;
ALTER TABLE learning_roadmap ALTER COLUMN legacy_generation_source SET DEFAULT FALSE;
ALTER TABLE learning_roadmap_history ADD COLUMN generation_job_id TEXT, ADD COLUMN generation_fence BIGINT;

ALTER TABLE initial_roadmap_delivery ADD COLUMN source_job_id TEXT, ADD COLUMN source_job_fence BIGINT;
