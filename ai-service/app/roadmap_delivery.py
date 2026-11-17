"""Read-only Telegram projection of the saved SP-08/09/10 initial roadmap."""
from __future__ import annotations


SKILLS = {'speaking': 'Разговор', 'listening': 'Аудирование',
          'grammar': 'Грамматика', 'vocabulary': 'Словарь'}
KNOWLEDGE = {
    'Known': 'тема знакома; задача требует повторной проверки',
    'Not Known': 'трудность с темой подтверждена',
    'Presumed Not Known': 'трудность с темой пока предположительная',
    'Unknown': 'по теме пока недостаточно наблюдений',
}
PRIORITY_REASONS = {
    'A confirmed difficulty is relevant to the learner goal.': 'Подтверждённая трудность важна для твоей цели.',
    'A topic difficulty is recorded; this specific problem still needs verification.':
        'Трудность с темой зафиксирована; конкретную задачу ещё проверим.',
    'This is proposed coverage; the gap still needs verification.':
        'Тема входит в предполагаемый путь; сначала проверим, есть ли здесь пробел.',
    'The learner requested this work now.': 'Ты попросил заняться этим сейчас.',
}


def _priority_reason(reason: str) -> str:
    goal_note = ' It supports the learner goal.'
    goal = reason.endswith(goal_note)
    base = reason[:-len(goal_note)] if goal else reason
    readable = PRIORITY_REASONS.get(base, base)
    return readable + (' Это также связано с твоей целью.' if goal else '')


def render_initial_roadmap(state: dict, saved: dict, priority: dict,
                           catalog: dict, timeline: dict, tasks: dict[str, dict]) -> str:
    """Render existing facts; never infer a level, gap, date, or Task score."""
    roadmap = saved['document']
    decision = (priority.get('priority') or {}).get('document') or {}
    if (priority.get('stale') or priority.get('roadmapRevision') != saved['revision'] or
            decision.get('trigger') != 'initial' or
            decision.get('roadmapRevision') != saved['revision']):
        raise ValueError('initial_priority_not_ready')
    sequence = roadmap['sequence']
    by_id = {entry['taskId']: entry for entry in sequence}
    ordered = decision.get('orderedTasks') or []
    if not sequence or {entry['taskId'] for entry in ordered} != set(by_id):
        raise ValueError('incomplete_roadmap_order')
    topics = {topic['id']: topic for topic in catalog['topics']}
    goal = (state.get('goal') or {}).get('content') or {}
    if goal.get('value') != roadmap['goal'] or state['profile']['targetLevel'] != roadmap['targetLevel']:
        raise ValueError('stale_roadmap')
    current = state['profile']['currentLevels']
    system = current['speaky']['overall']['level']
    reported = current['selfAssessment']['overall']['level']
    lines = ['**Твой предварительный маршрут после пробного урока**',
             f"Цель: {roadmap['goal']}",
             f"Текущий уровень по наблюдениям Speaky: {system if system != 'Unknown' else 'пока не определён'}.",
             f"Твоя самооценка: {reported if reported != 'Unknown' else 'не указана'}.",
             f"Общая цель по CEFR: {roadmap['targetLevel']}.",
             'Это рабочая оценка по пробному уроку, а не результат экзамена.',
             '', '**Ближайшие задачи**']
    next_id = decision.get('nextTaskId')
    for index, item in enumerate(ordered[:3], 1):
        entry = by_id[item['taskId']]
        topic = topics.get(entry['topicId'])
        if topic is None:
            raise ValueError('roadmap_catalog_missing_topic')
        lines.append(f"{index}. {SKILLS.get(entry['skillId'], entry['skillId'])}: {topic.get('title') or topic.get('objective') or entry['topicId']}")
    lines += ['', f"**Предполагаемый полный путь до {roadmap['targetLevel']}** · {len(ordered)} задач"]
    for index, item in enumerate(ordered, 1):
        entry = by_id[item['taskId']]
        topic = topics.get(entry['topicId'])
        if topic is None:
            raise ValueError('roadmap_catalog_missing_topic')
        task = tasks.get(item['taskId'])
        if task is None:
            raise ValueError('roadmap_task_missing')
        evidence = KNOWLEDGE[entry['knowledge']]
        if entry['provisional'] and entry['knowledge'] == 'Not Known':
            evidence += '; конкретная задача ещё гипотеза'
        name = topic.get('title') or topic.get('objective') or entry['topicId']
        task_status = ('ещё не начата (0%)' if task['lifecycle'] == 'Identified' and task['progress'] == 0
                       else 'уже есть в маршруте; измеренный прогресс здесь пока не показан')
        lines += ['', f"{index}. {SKILLS.get(entry['skillId'], entry['skillId'])} · {entry['cefr']} · {name}",
                  f"Статус: {task_status}; {evidence}.",
                  f"Зачем: {topic.get('objective') or entry['reason']}"]
        if item['taskId'] == next_id:
            lines.append(f"Почему сейчас: {_priority_reason(item['reason'])}")
    lines += ['', '**Ближайший фокус**']
    if next_id and next_id in by_id:
        topic = topics[by_id[next_id]['topicId']]
        lines.append(f"Начнём с «{topic.get('title') or topic.get('objective') or by_id[next_id]['topicId']}». {_priority_reason(next(x['reason'] for x in ordered if x['taskId'] == next_id))}")
    else:
        lines.append('Сначала уточним маршрут вместе: следующая задача пока не выбрана.')
    if timeline.get('status') == 'provisional' and timeline.get('roadmapRevision') == saved['revision']:
        dates = timeline.get('dates') or {}
        if dates.get('earliest') and dates.get('latest'):
            lines += ['', '**Ориентир по времени**',
                      f"При согласованной нагрузке: примерно с {dates['earliest']} по {dates['latest']}.",
                      'Это широкий предварительный диапазон, не обещание даты.']
    lines += ['', 'Если хочешь, обсудим здесь удобный ритм следующих занятий и уточним предположения по ходу практики.']
    return '\n'.join(lines)


async def prepare_initial_roadmap(backend, scope: str, expected_revision: int) -> str:
    saved = (await backend.learning_roadmap(scope))['roadmap']
    if saved['revision'] != expected_revision:
        raise ValueError('stale_roadmap')
    state = await backend.learning_state(scope)
    priority = await backend.learning_roadmap_priority(scope)
    catalog = await backend.catalog(version=saved['catalog_version'])
    timeline = await backend.roadmap_timeline(scope)
    tasks, offset = {}, 0
    while True:
        page = await backend.learning_tasks(scope, offset=offset)
        tasks.update({task['id']: task for task in page['items']})
        offset = page.get('nextOffset')
        if offset is None:
            break
    return render_initial_roadmap(state, saved, priority, catalog, timeline, tasks)
