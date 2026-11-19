"""SP-12B: approved Task Plan templates and stage navigation policy.

This module does not grade observations or run a lesson. SP-19 supplies stage
results; SP-16 decides what to do when the learner changes the activity.
"""
from __future__ import annotations

import math
from dataclasses import dataclass
from typing import Literal

from app.foundation_client import FoundationClient


Method = Literal['Explanation', 'Exercises', 'Conversation', 'Exposure']
StageSpec = tuple[Method, float | None]


# A None weight means the one supported binary condition. These are the six
# baseline MVP configurations approved for SP-12B, not a Topic/CEFR curriculum.
TEMPLATES: dict[tuple[str, str], tuple[StageSpec, ...]] = {
    ('grammar', 'grammar_unknown'): (('Explanation', None), ('Exercises', 0.3), ('Conversation', 0.7)),
    ('grammar', 'grammar_application'): (('Exercises', 0.3), ('Conversation', 0.7)),
    ('speaking', 'speaking_barrier'): (('Conversation', 1.0),),
    ('listening', 'listening_comprehension'): (('Exposure', 1.0),),
    ('vocabulary', 'vocabulary_passive'): (('Exercises', 1.0),),
    ('vocabulary', 'vocabulary_active'): (('Exercises', 0.3), ('Conversation', 0.7)),
}


class PlanInputError(ValueError):
    """A Task and a published catalog cannot safely select a template."""


@dataclass(frozen=True)
class PlanProposal:
    template_id: str
    definition: dict
    first_stage_id: str


@dataclass(frozen=True)
class StageAdvice:
    action: Literal['continue', 'advance', 'reconsider', 'stage_complete', 'pause_task']
    next_stage_id: str | None = None


def propose_plan(task: dict, catalog: dict) -> PlanProposal | None:
    """Select only a catalog-compatible, approved template; None means no Plan."""
    if catalog.get('schemaVersion') != 2 or catalog.get('version') != task.get('catalog_version'):
        raise PlanInputError('catalog_version_mismatch')
    skill, topic, problem = (task.get(key) for key in ('skill_id', 'topic_id', 'problem_id'))
    topics = [item for item in catalog.get('topics', []) if item.get('id') == topic]
    problems = [item for item in catalog.get('problems', []) if item.get('id') == problem]
    if (len(topics) != 1 or len(problems) != 1 or
            topics[0].get('skillId') != skill or problems[0].get('skillId') != skill or
            not topics[0].get('objective') or not topics[0].get('scope')):
        raise PlanInputError('invalid_task_catalog_links')
    stages = TEMPLATES.get((skill, problem))
    if stages is None:
        return None

    definition_stages = []
    for method, weight in stages:
        stage = {'id': method.lower(), 'method': method, 'kind': 'binary' if weight is None else 'numeric',
                 'required': True}
        if weight is None:
            stage['condition'] = 'understanding_demonstrated'
        else:
            stage['weight'] = weight
        definition_stages.append(stage)
    order = [stage['id'] for stage in definition_stages]
    transitions = [edge for first, second in zip(order, order[1:])
                   for edge in ({'from': first, 'to': second}, {'from': second, 'to': first})]
    return PlanProposal(
        template_id=f'sp12b.v1.{problem}',
        definition={'schemaVersion': 1, 'stages': definition_stages, 'order': order, 'transitions': transitions},
        first_stage_id=order[0],
    )


def advise_stage(definition: dict, current_stage_id: str, *, score: float | None = None,
                 understanding_demonstrated: bool = False, learner_refused: bool = False) -> StageAdvice:
    """Interpret an externally supplied SP-19 result without changing a Score."""
    order = definition['order']
    if current_stage_id not in order:
        raise PlanInputError('stage_not_in_plan')
    if learner_refused:
        return StageAdvice('pause_task')
    stage = next(item for item in definition['stages'] if item['id'] == current_stage_id)
    if stage['kind'] == 'binary':
        if score is not None:
            raise PlanInputError('binary_stage_has_no_score')
        complete = understanding_demonstrated
    else:
        if (understanding_demonstrated or isinstance(score, bool) or
                not isinstance(score, (int, float)) or not math.isfinite(score) or not 0 <= score <= 100):
            raise PlanInputError('numeric_stage_score_required')
        if score <= 15:
            index = order.index(current_stage_id)
            previous = order[index - 1] if index > 0 else None
            if previous is not None and {'from': current_stage_id, 'to': previous} not in definition['transitions']:
                previous = None
            return StageAdvice('reconsider', previous)
        complete = score >= 85
    if not complete:
        return StageAdvice('continue')
    index = order.index(current_stage_id)
    if index == len(order) - 1:
        return StageAdvice('stage_complete')
    next_stage_id = order[index + 1]
    if {'from': current_stage_id, 'to': next_stage_id} not in definition['transitions']:
        raise PlanInputError('stage_transition_not_allowed')
    return StageAdvice('advance', next_stage_id)


def stage_reference(plan: dict, stage_id: str) -> dict:
    """Return the immutable identity expected by SP-13 and SP-17/19."""
    if stage_id not in plan['definition']['order']:
        raise PlanInputError('stage_not_in_plan')
    return {'taskId': plan['taskId'], 'planId': plan['id'],
            'planRevision': plan['revision'], 'stageId': stage_id}


class PedagogicalPlanner:
    def __init__(self, backend: FoundationClient):
        self.backend = backend

    async def ensure_plan(self, scope: str, task_id: str, operation_id: str) -> dict:
        """Attach a baseline Plan to a real Task through the SP-12A transaction."""
        task = (await self.backend.learning_task(scope, task_id))['task']
        linked = task['details'].get('plan')
        if linked is not None:
            stored = await self.backend.learning_plan(scope, linked['id'])
            return {'status': 'existing', 'plan': stored['plan']}
        catalog = await self.backend.catalog(version=task['catalog_version'], topic_id=task['topic_id'])
        proposal = propose_plan(task, catalog)
        if proposal is None:
            return {'status': 'template_missing', 'taskId': task_id, 'problemId': task['problem_id']}
        created = await self.backend.create_learning_plan(
            scope, operation_id, task_id=task_id, expected_task_revision=task['revision'],
            definition=proposal.definition, current_stage_id=proposal.first_stage_id,
            component='sp12b.pedagogical_plan',
        )
        return {'status': 'created', 'templateId': proposal.template_id, 'plan': created['plan']}

    async def apply_stage_result(self, scope: str, operation_id: str, plan: dict, *,
                                 score: float | None = None, understanding_demonstrated: bool = False,
                                 learner_refused: bool = False, return_to_previous: bool = False) -> dict:
        """Apply one planned cursor move from a caller-pinned Plan snapshot.

        The caller supplies the verified SP-19 result and must reuse its Plan
        snapshot plus operation ID on retry. No score or attempt state is written.
        """
        state = plan['currentState']
        current = state['currentStageId']
        if current is None:
            raise PlanInputError('current_stage_required')
        advice = advise_stage(plan['definition'], current, score=score,
                              understanding_demonstrated=understanding_demonstrated,
                              learner_refused=learner_refused)
        if return_to_previous and advice.action != 'reconsider':
            raise PlanInputError('return_not_indicated')
        if return_to_previous and advice.next_stage_id is None:
            raise PlanInputError('previous_stage_unavailable')
        target = advice.next_stage_id if advice.action == 'advance' or return_to_previous else None
        if target is None:
            return {'advice': advice, 'plan': plan}
        changed = await self.backend.select_learning_stage(
            scope, operation_id, plan_id=plan['id'], expected_plan_revision=plan['revision'],
            expected_state_revision=state['stateRevision'], current_stage_id=target,
            reason='plan_transition', component='sp12b.pedagogical_plan',
        )
        return {'advice': advice, 'plan': changed['plan']}
