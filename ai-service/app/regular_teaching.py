"""Prepare the approved Task Plan and validated Content for a regular lesson.

Compute handlers are read-only. Foundation applies each result under its job
fence and student transaction; a job-scoped token cannot issue writes here.
"""
from __future__ import annotations

from app.foundation_client import BackendFailure
from app.learning_content_generation import ContentRequest, LearningContentGenerator
from app.pedagogical_plan import propose_plan


ACTION_FOR_STAGE = {
    ('Explanation', 'grammar_unknown'): 'direct',
    ('Exercises', 'grammar_unknown'): 'sentence_construction',
    ('Exercises', 'grammar_application'): 'sentence_transformation',
    ('Exercises', 'vocabulary_passive'): 'meaning_recognition',
    ('Exercises', 'vocabulary_active'): 'word_recall',
    ('Exposure', 'listening_comprehension'): 'listening_reconstruction',
    ('Conversation', 'grammar_unknown'): 'topic_driven',
    ('Conversation', 'grammar_application'): 'topic_driven',
    ('Conversation', 'vocabulary_active'): 'topic_driven',
    ('Conversation', 'speaking_barrier'): 'topic_driven',
}

# A different Action stays in the same Stage and uses a reviewed SP-13 kind.
ALTERNATE_ACTION_FOR_STAGE = {
    ('Explanation', 'grammar_unknown'): 'comparison',
    ('Exercises', 'grammar_unknown'): 'sentence_transformation',
    ('Exercises', 'grammar_application'): 'sentence_construction',
    ('Exercises', 'vocabulary_passive'): 'meaning_matching',
    ('Exercises', 'vocabulary_active'): 'rapid_response',
    ('Exposure', 'listening_comprehension'): 'listening_comprehension',
    ('Conversation', 'grammar_unknown'): 'situational',
    ('Conversation', 'grammar_application'): 'situational',
    ('Conversation', 'vocabulary_active'): 'situational',
    ('Conversation', 'speaking_barrier'): 'situational',
}


class RegularTeachingPreparation:
    def __init__(self, backend, model):
        self.backend = backend
        self.content = LearningContentGenerator(backend, model)

    async def plan_proposal(self, scope: str) -> dict | None:
        """Select only the current SP-09 Task; no Plan write in compute."""
        try:
            decision = await self.backend.learning_roadmap_priority(scope)
        except BackendFailure as error:
            if error.code == 'roadmap_not_found' or error.status == 404:
                return None
            raise
        priority = decision.get('priority')
        if decision.get('stale') or not priority or not priority.get('document'):
            return None
        task_id = priority['document'].get('nextTaskId')
        if not task_id:
            return None
        task = (await self.backend.learning_task(scope, task_id))['task']
        if task.get('lifecycle') == 'Mastered':
            return None
        proposal = None
        if not task.get('details', {}).get('plan'):
            catalog = await self.backend.catalog(version=task['catalog_version'], topic_id=task['topic_id'])
            chosen = propose_plan(task, catalog)
            if chosen is None:
                return {'taskId': task_id, 'taskRevision': task['revision'],
                        'priorityRevision': priority['revision'], 'status': 'template_missing'}
            proposal = {'definition': chosen.definition, 'currentStageId': chosen.first_stage_id}
        return {'taskId': task_id, 'taskRevision': task['revision'],
                'priorityRevision': priority['revision'], 'status': 'ready',
                'planProposal': proposal}

    async def content_document(self, scope: str, inputs: dict) -> dict:
        """Return one independently reviewed document for the pinned current Stage."""
        stage = await self.backend.learning_stage(scope, task_id=inputs['taskId'],
            plan_id=inputs['planId'], plan_revision=inputs['planRevision'], stage_id=inputs['stageId'])
        task = stage['task']
        action = ACTION_FOR_STAGE.get((stage['stage']['method'], task['problem_id']))
        if action is None:
            raise ValueError('missing_action_template')
        state = await self.backend.learning_state(scope)
        levels = state['profile']['currentLevels']['speaky']
        skill_level = levels['skills'][task['skill_id']]['level']
        cefr = skill_level if skill_level != 'Unknown' else levels['overall']['level']
        prepared = await self.content.prepare(scope, ContentRequest(
            task_id=inputs['taskId'], plan_id=inputs['planId'],
            plan_revision=inputs['planRevision'], stage_id=inputs['stageId'],
            action_kind=action, cefr=cefr))
        return {'document': prepared.document}
