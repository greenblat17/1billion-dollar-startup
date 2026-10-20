ALTER TABLE onboarding_nudges ADD COLUMN copy_kind TEXT
    CHECK (copy_kind IN ('general', 'personalized'));

UPDATE onboarding_followup_schedule SET dirty = TRUE, updated_at = now()
WHERE first_practice_at IS NOT NULL AND stopped_at IS NULL;
