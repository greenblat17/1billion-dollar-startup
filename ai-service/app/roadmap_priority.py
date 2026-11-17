"""SP-09 operational priority decision over an existing SP-08 roadmap.

This module never infers mastery, computes learning scores, or creates lesson content.
Its caller supplies explicit learner decisions and personal prerequisite Task IDs.
"""
from __future__ import annotations


REVIEW_TRIGGERS = {'initial', 'post_lesson_review', 'goal_change', 'learner_request',
                   'task_mastered', 'proposal_accepted'}
ACTIVE = {'Identified', 'In Progress', 'Reopened'}
LEVELS = ('A1', 'A2', 'B1', 'B2', 'C1')


def prioritize(roadmap: dict, tasks: list[dict], *, trigger: str,
               prerequisite_task_ids: dict[str, list[str]] | None = None,
               deferred_task_ids: list[str] | None = None,
               requested_task_id: str | None = None,
               goal_focus_task_ids: list[str] | None = None,
               declined_problems: list[dict] | None = None,
               accepted_tasks: list[dict] | None = None,
               topic_knowledge: dict[str, str] | None = None) -> dict:
    """Return an explainable order; only deliberate review triggers may change it.

    Existing task progress/lifecycle are read, never patched. An occasional answer
    cannot be passed as a trigger. The caller persists the decision atomically.
    """
    if trigger not in REVIEW_TRIGGERS:
        raise ValueError('roadmap_review_required')
    row = roadmap.get('roadmap', roadmap)
    document = row.get('document', row)
    revision = row.get('revision')
    if document.get('scope') != 'initial_roadmap' or not isinstance(revision, int):
        raise ValueError('current_roadmap_required')
    by_id = {task['id']: task for task in tasks}
    prerequisites = prerequisite_task_ids or {}
    deferred = set(deferred_task_ids or [])
    deferred.discard(requested_task_id)
    goal_focus = set(goal_focus_task_ids or [])
    declined = declined_problems or []
    sequence = list(document['sequence']) + list(accepted_tasks or [])
    entries = []
    seen = set()
    for initial in sequence:
        task_id = initial.get('taskId')
        if not task_id or task_id in seen or task_id not in by_id:
            raise ValueError('invalid_roadmap_task')
        seen.add(task_id)
        task = by_id[task_id]
        if (task['topic_id'], task['problem_id']) != (initial['topicId'], initial['problemId']):
            raise ValueError('invalid_roadmap_task')
        if task['lifecycle'] not in ACTIVE:
            continue
        personal_prereqs = prerequisites.get(task_id, [])
        if task_id in personal_prereqs or any(dep not in by_id for dep in personal_prereqs):
            raise ValueError('invalid_personal_prerequisite')
        if len(personal_prereqs) != len(set(personal_prereqs)):
            raise ValueError('duplicate_personal_prerequisite')
        outstanding = [dep for dep in personal_prereqs if by_id[dep]['lifecycle'] != 'Mastered']
        declined_match = any(item.get('topicId') == task['topic_id'] and
                             item.get('problemId') == task['problem_id'] for item in declined)
        if declined_match and task_id == requested_task_id:
            raise ValueError('prior_decline_requires_acceptance')
        is_deferred = declined_match or (task_id in deferred and task_id != requested_task_id)
        status = (topic_knowledge or {}).get(task['topic_id'], initial['knowledge'])
        evidence = 'confirmed' if status == 'Not Known' and not initial['provisional'] else 'hypothesis'
        # A confirmed Topic gap with a provisional Problem remains a hypothesis for this Task.
        if status == 'Not Known' and initial['provisional']:
            evidence = 'topic_confirmed_problem_hypothesis'
        if status == 'Known' and task['lifecycle'] != 'Reopened':
            evidence = 'known'
        if task_id == requested_task_id:
            reason = 'The learner requested this work now.'
        elif is_deferred:
            reason = 'The learner asked to defer this work; its knowledge and progress stay unchanged.'
        elif evidence == 'known':
            reason = 'The topic is currently Known; review this Task before scheduling more work.'
        elif outstanding:
            reason = 'Personal prerequisites need work first: ' + ', '.join(outstanding)
        elif evidence == 'confirmed':
            reason = 'A confirmed difficulty is relevant to the learner goal.'
        elif evidence == 'topic_confirmed_problem_hypothesis':
            reason = 'The topic difficulty is recorded; this specific problem still needs verification.'
        else:
            reason = 'This is proposed coverage; the gap still needs verification.'
        focused = task_id in goal_focus if goal_focus_task_ids is not None else bool(initial.get('goalFocus'))
        if focused:
            reason += ' It supports the learner goal.'
        entries.append({'taskId': task_id, 'priority': None, 'reason': reason,
                        'evidence': evidence, 'prerequisiteTaskIds': personal_prereqs,
                        '_blocked': bool(outstanding) or (evidence == 'known' and task_id != requested_task_id),
                        '_deferred': is_deferred,
                        '_requested': task_id == requested_task_id,
                        '_level': LEVELS.index(initial['cefr']),
                        '_goal': focused,
                        '_initial': initial['order']})
    if requested_task_id and requested_task_id not in seen:
        raise ValueError('requested_task_not_in_roadmap')
    if any(dep not in seen for item in entries for dep in item['prerequisiteTaskIds']):
        raise ValueError('prerequisite_not_in_roadmap')
    # Personal prerequisites override level ordering; stable initial order breaks ties.
    available = {item['taskId']: item for item in entries}
    ordered = []
    while available:
        ready = [item for item in available.values() if not any(dep in available for dep in item['prerequisiteTaskIds'])]
        if not ready:
            raise ValueError('personal_prerequisite_cycle')
        ready.sort(key=lambda item: (item['_deferred'], item['_blocked'],
                   not item['_requested'], item['_level'], not item['_goal'],
                   item['evidence'] == 'hypothesis', item['_initial']))
        chosen = ready[0]
        ordered.append(chosen)
        del available[chosen['taskId']]
    selectable = [item for item in ordered if not item['_deferred'] and not item['_blocked']]
    next_id = selectable[0]['taskId'] if selectable else None
    focus = [item['taskId'] for item in selectable[:3] if item['taskId'] == next_id or
             item.get('_goal') or by_id[item['taskId']]['skill_id'] != by_id[next_id]['skill_id']] if next_id else []
    for index, item in enumerate(ordered):
        item['priority'] = index + 1
        for key in tuple(item):
            if key.startswith('_'):
                del item[key]
    return {'schemaVersion': 1, 'roadmapRevision': revision, 'trigger': trigger,
            'orderedTasks': ordered, 'nextTaskId': next_id, 'focusTaskIds': focus,
            'deferredTaskIds': sorted(deferred), 'declinedProblems': declined,
            'needsRoadmapReview': next_id is None,
            'explanation': next((item['reason'] for item in ordered if item['taskId'] == next_id),
                                'No available Task; review the Roadmap.'),
            'scope': 'operational_priority'}


class RoadmapPriorityService:
    """Read current Foundation state, decide, then submit an optimistic revision."""

    def __init__(self, backend):
        self.backend = backend

    async def plan(self, scope: str, *, trigger: str,
                     requested_task_id: str | None = None,
                     deferred_task_ids: list[str] | None = None,
                     prerequisite_task_ids: dict[str, list[str]] | None = None,
                     goal_focus_task_ids: list[str] | None = None) -> dict:
        roadmap = (await self.backend.learning_roadmap(scope))['roadmap']
        priority = await self.backend.learning_roadmap_priority(scope)
        tasks = []
        offset = 0
        while True:
            page = await self.backend.learning_tasks(scope, offset=offset)
            tasks.extend(page['items'])
            offset = page.get('nextOffset')
            if offset is None:
                break
            if len(tasks) > 1000:
                raise ValueError('learning_task_limit')
        current = (priority.get('priority') or {}).get('document') or {}
        decisions = priority['problemDecisions']
        knowledge = {}
        offset = 0
        while True:
            page = await self.backend.topic_knowledge(scope, roadmap['catalog_version'], offset=offset)
            knowledge.update({item['topic_id']: item['status'] for item in page['items']})
            offset = page.get('nextOffset')
            if offset is None:
                break
            if len(knowledge) > 500:
                raise ValueError('topic_state_limit')
        accepted_ids = {item['taskId'] for item in decisions if item['decision'] == 'accepted' and item['taskId']}
        base_ids = {entry['taskId'] for entry in roadmap['document']['sequence']}
        extra = []
        if accepted_ids - base_ids:
            catalog = await self.backend.catalog(version=roadmap['catalog_version'])
            topics = {topic['id']: topic for topic in catalog['topics']}
            by_id = {task['id']: task for task in tasks}
            for index, task_id in enumerate(sorted(accepted_ids - base_ids), start=len(base_ids) + 1):
                task = by_id[task_id]
                topic = topics[task['topic_id']]
                extra.append({'taskId': task_id, 'topicId': task['topic_id'],
                              'problemId': task['problem_id'], 'skillId': task['skill_id'],
                              'cefr': topic['cefr'], 'knowledge': 'Unknown', 'provisional': True,
                              'goalFocus': False, 'order': index})
        declined = [{'topicId': item['topicId'], 'problemId': item['problemId']}
                    for item in decisions if item['decision'] == 'declined']
        valid_ids = base_ids | accepted_ids
        previous_prerequisites = {entry['taskId']: [dep for dep in entry['prerequisiteTaskIds'] if dep in valid_ids]
                                  for entry in current.get('orderedTasks', []) if entry['taskId'] in valid_ids}
        result = prioritize(roadmap, tasks, trigger=trigger, accepted_tasks=extra,
                            requested_task_id=requested_task_id,
                            deferred_task_ids=deferred_task_ids if deferred_task_ids is not None
                            else [id for id in current.get('deferredTaskIds', []) if id in valid_ids],
                            prerequisite_task_ids=prerequisite_task_ids if prerequisite_task_ids is not None
                            else previous_prerequisites,
                            goal_focus_task_ids=goal_focus_task_ids, declined_problems=declined,
                            topic_knowledge=knowledge)
        return {'expectedRevision': (priority.get('priority') or {}).get('revision', 0),
                'decision': result}

    async def review(self, scope: str, operation_id: str, *, trigger: str,
                     requested_task_id: str | None = None,
                     deferred_task_ids: list[str] | None = None,
                     prerequisite_task_ids: dict[str, list[str]] | None = None,
                     goal_focus_task_ids: list[str] | None = None) -> dict:
        args = await self.plan(scope, trigger=trigger, requested_task_id=requested_task_id,
                               deferred_task_ids=deferred_task_ids,
                               prerequisite_task_ids=prerequisite_task_ids,
                               goal_focus_task_ids=goal_focus_task_ids)
        return await self.backend.command(scope, operation_id, 'learning.roadmap.priority.save', args)

    async def decide_problem(self, scope: str, operation_id: str, *, topic_id: str, problem_id: str,
                             skill_id: str, decision: str, source: str, reason: str,
                             decision_id: str | None = None) -> dict:
        """Only call after the learner accepts/declines during a lesson or directly asks."""
        return await self.backend.command(scope, operation_id, 'learning.roadmap.problem.decide',
                                          {'topicId': topic_id, 'problemId': problem_id,
                                           'skillId': skill_id, 'decision': decision, 'source': source,
                                           'reason': reason, 'decisionId': decision_id})
