-- Retain the outcome of roadmap review, including honest insufficient-evidence/keep decisions.
ALTER TABLE foundation_job ADD COLUMN result JSONB;
