ALTER TABLE execution_scope DROP CONSTRAINT execution_scope_role_check;
ALTER TABLE execution_scope ADD CONSTRAINT execution_scope_role_check
    CHECK (role IN ('text', 'voice', 'learning', 'operator', 'agent_voice_grant'));
