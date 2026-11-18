"""SP-08 initial roadmap. Task storage remains owned by SP-04.

The initial order is a pedagogical proposal, not a catalog dependency graph.
SP-09 may change priorities after new evidence or a learner request.
"""
from __future__ import annotations

import re
from dataclasses import dataclass


LEVELS = ('A1', 'A2', 'B1', 'B2', 'C1')
SKILLS = ('speaking', 'listening', 'grammar', 'vocabulary')
PROBLEMS = {
    'speaking': 'speaking_barrier',
    'listening': 'listening_comprehension',
    'grammar': 'grammar_unknown',
    'vocabulary': 'vocabulary_active',
}
WORK_MARKERS = frozenset(('work', 'job', 'career', 'professional', 'interview', 'office',
                          'работ', 'рабоч', 'карьер', 'собесед', 'професс'))
GOAL_TOPICS = {
    'G01': {'introductions', 'social', 'short-exchange', 'everyday', 'unprepared', 'interaction', 'relationships'},
    'G02': {'travel', 'transactions', 'announcements', 'numbers', 'repair'},
    'G03': {'work-basics', 'work', 'work-communication', 'interview', 'professional', 'meetings', 'negotiation', 'specialised'},
    'G04': {'work', 'lecture', 'instructions', 'explanation', 'presentation', 'synthesis', 'specialised'},
    'G05': {'needs', 'home', 'travel', 'shopping', 'health', 'society', 'everyday', 'transactions'},
    'G06': {'argument', 'narrative', 'precision', 'presentation', 'lecture', 'inference', 'synthesis'},
    'G07': {'media', 'stories', 'narrative', 'implicit', 'complex-audio', 'connected-speech', 'idioms'},
    'G08': {'preferences', 'experiences', 'opinions', 'problem-solving', 'abstract', 'sustained-discussion'},
}
GENERIC_GOAL_WORDS = frozenset(('хочу', 'нужно', 'уметь', 'английский', 'английском', 'говорить', 'общаться',
                                'свободно', 'learn', 'want', 'speak', 'english', 'better', 'with', 'about'))


@dataclass(frozen=True)
class Candidate:
    topic_id: str
    skill_id: str
    cefr: str
    problem_id: str
    knowledge: str
    provisional: bool
    reason: str
    order_reason: str
    goal_focus: bool
    personal_focus: bool
    existing_task: dict | None = None


def _level(assessment: dict | None) -> str:
    value = (assessment or {}).get('level', 'Unknown')
    return value if value in LEVELS else 'Unknown'


def _personal_focus(words: set[str], details: set[str], topic: dict) -> bool:
    if topic['skillId'] not in {'speaking', 'listening', 'vocabulary'}:
        return False
    text = ' '.join(str(topic.get(key, '')).lower() for key in ('id', 'title', 'objective', 'scope', 'coverageArea'))
    work = any(any(word.startswith(marker) for marker in WORK_MARKERS) for word in words)
    if work and any(marker in text for marker in ('work', 'professional', 'interview', 'negotiat', 'business')):
        return True
    return bool(details.intersection(re.findall(r'[^\W\d_]+', text)))


def build_roadmap(state: dict, catalog: dict, knowledge: list[dict], tasks: list[dict]) -> dict:
    """Select complete estimated coverage without promoting hypotheses to observations."""
    if state.get('schemaVersion') != 1 or catalog.get('schemaVersion') != 2:
        raise ValueError('unsupported_roadmap_input')
    target = state['profile']['targetLevel']
    if target not in LEVELS:
        raise ValueError('target_level_required')
    goal_row = state.get('goal') or {}
    goal_content = goal_row.get('content') or {}
    goal = goal_content.get('personal_goal', goal_content.get('value'))
    if not isinstance(goal, str) or not goal.strip() or not isinstance(goal_row.get('revision'), int):
        raise ValueError('goal_required')
    goal_topics = set().union(*(GOAL_TOPICS.get(goal_id, ()) for goal_id in goal_content.get('goals', ())))
    goal_words = set(re.findall(r'[^\W\d_]+', goal.lower()))
    goal_details = ({word for word in goal_words if len(word) >= 4} - GENERIC_GOAL_WORDS
                    if 'personal_goal' in goal_content else set())
    target_index = LEVELS.index(target)
    current = state['profile']['currentLevels']
    system = current['speaky']['skills']
    self_report = current['selfAssessment']['skills']
    by_knowledge = {row['topic_id']: row for row in knowledge}
    by_task = {(row['topic_id'], row['problem_id']): row for row in tasks}
    selected: list[Candidate] = []
    for topic in catalog['topics']:
        skill = topic['skillId']
        if skill not in SKILLS or topic['cefr'] not in LEVELS:
            continue
        band = LEVELS.index(topic['cefr'])
        status = by_knowledge.get(topic['id'], {}).get('status', 'Unknown')
        system_level = _level(system.get(skill))
        reported_level = _level(self_report.get(skill))
        anchor = system_level if system_level != 'Unknown' else reported_level
        anchor_index = LEVELS.index(anchor) if anchor != 'Unknown' else 0
        problem = PROBLEMS[skill]
        matching = [task for (topic_id, _), task in by_task.items() if topic_id == topic['id']]
        reopened = next((task for task in matching if task['lifecycle'] == 'Reopened'), None)
        active = reopened or next((task for task in matching if task['lifecycle'] != 'Mastered'), None)
        if active:
            problem = active['problem_id']
        elif status == 'Known' or any(task['lifecycle'] == 'Mastered' for task in matching):
            continue
        if band > target_index or (band < anchor_index and status not in {'Not Known', 'Presumed Not Known'} and not active):
            continue
        if status == 'Known' and not reopened:
            continue
        # Topic knowledge does not diagnose which catalog Problem applies. New Problem links are hypotheses.
        provisional = not reopened
        if reopened:
            reason = 'Previously mastered problem was reopened; retain its task and history.'
        elif status == 'Not Known':
            reason = 'Recorded topic difficulty supports work here; the selected Problem type is provisional.'
        elif status == 'Presumed Not Known':
            reason = 'A recorded topic difficulty is a hypothesis, not a confirmed gap.'
        else:
            reason = 'Coverage hypothesis; topic knowledge and problem type require verification in practice.'
        if system_level == 'Unknown':
            reason += (' Skill starting level uses self-report provisionally.' if reported_level != 'Unknown'
                       else ' Skill starting level is unknown; coverage begins at A1 provisionally.')
        personal_focus = _personal_focus(goal_words, goal_details, topic)
        focus = personal_focus or topic['id'].split('-', 2)[-1] in goal_topics
        order_reason = ('Earlier level and recorded gap first; ' if band < anchor_index else
                        'Progress from the current skill level; ')
        order_reason += ('goal-related context is brought forward within this level.' if focus else
                         'catalog level supplies a broad sequence, not a fixed prerequisite.')
        selected.append(Candidate(topic['id'], skill, topic['cefr'], problem, status,
                                  provisional, reason, order_reason, focus, personal_focus, active))
    # Keep each skill independent. Within a level, bring the learner's weakest measured skill forward.
    def key(item: Candidate):
        measured = _level(system.get(item.skill_id))
        reported = _level(self_report.get(item.skill_id))
        anchor = measured if measured != 'Unknown' else reported
        start = LEVELS.index(anchor) if anchor != 'Unknown' else 0
        return (LEVELS.index(item.cefr), start, 0 if item.knowledge == 'Not Known' else 1,
                0 if item.personal_focus else 1 if item.goal_focus else 2, SKILLS.index(item.skill_id), item.topic_id)
    selected.sort(key=key)
    entries = [dict(topicId=item.topic_id, skillId=item.skill_id, cefr=item.cefr,
                    problemId=item.problem_id, knowledge=item.knowledge,
                    provisional=item.provisional, reason=item.reason,
                    orderReason=item.order_reason, goalFocus=item.goal_focus,
                    taskId=item.existing_task['id'] if item.existing_task else None,
                    order=index + 1) for index, item in enumerate(selected)]
    included = {entry['taskId'] for entry in entries if entry['taskId']}
    assumptions = []
    for skill in SKILLS:
        measured = _level(system.get(skill))
        reported = _level(self_report.get(skill))
        start = measured if measured != 'Unknown' else reported
        source = 'system' if measured != 'Unknown' else ('self_report' if reported != 'Unknown' else 'unknown')
        assumptions.append({'skillId': skill, 'startingLevel': start, 'source': source,
                            'basis': ('A1 coverage is proposed because no starting level is known.' if source == 'unknown'
                                      else 'Earlier levels are assumed as a starting basis; recorded gaps remain included.')})
    return {'schemaVersion': 1, 'catalogVersion': catalog['version'], 'goalRevision': goal_row['revision'],
            'targetLevel': target, 'goal': goal, 'provisional': (any(item.provisional for item in selected) or
                                                                any(item['source'] != 'system' for item in assumptions)),
            'sequence': entries, 'taskCount': len(entries),
            'assumptions': assumptions,
            'history': [{'taskId': task['id'], 'lifecycle': task['lifecycle']}
                        for task in tasks if task['id'] not in included],
            'scope': 'initial_roadmap', 'timeline': None}


class PersonalizedRoadmap:
    """Read-only job planner; Foundation applies Tasks and roadmap atomically."""
    def __init__(self, backend):
        self.backend = backend

    async def plan(self, scope: str, *, catalog_version: str | None = None) -> dict:
        state = await self.backend.learning_state(scope)
        catalog = await self.backend.catalog(version=catalog_version)
        if catalog.get('schemaVersion') != 2:
            raise ValueError('unsupported_catalog_schema')
        knowledge, offset = [], 0
        while True:
            page = await self.backend.topic_knowledge(scope, catalog['version'], offset=offset)
            knowledge.extend(page['items'])
            offset = page.get('nextOffset')
            if offset is None:
                break
            if len(knowledge) > 500:
                raise ValueError('topic_state_limit')
        tasks, offset = [], 0
        while True:
            page = await self.backend.learning_tasks(scope, offset=offset)
            tasks.extend(page['items'])
            offset = page.get('nextOffset')
            if offset is None:
                break
            if len(tasks) > 1000:
                raise ValueError('learning_task_limit')
        return build_roadmap(state, catalog, knowledge, tasks)
