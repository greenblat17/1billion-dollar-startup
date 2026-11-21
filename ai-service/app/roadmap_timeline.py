"""SP-10 read-only, provisional timeline projection for a saved SP-08 roadmap.

Cambridge's cumulative guided learning hours are a reference, not a measured
Speaky learner rate. This module never writes Tasks, order, or priorities.
"""
from __future__ import annotations

from datetime import date, timedelta
from math import ceil, isfinite


SOURCE = 'https://support.cambridgeenglish.org/hc/en-gb/articles/202838506-Guided-learning-hours'
GUIDED_HOURS = {'A1': (90, 100), 'A2': (180, 200), 'B1': (350, 400),
                'B2': (500, 600), 'C1': (700, 800)}
SKILLS = ('speaking', 'listening', 'grammar', 'vocabulary')


def _missing(reason: str, revision: int | None = None) -> dict:
    return {'schemaVersion': 1, 'status': 'insufficient_data', 'roadmapRevision': revision,
            'weeks': None, 'dates': None, 'deadline': None, 'reasons': [reason],
            'source': SOURCE, 'uncertainty': 'No reliable timeline can be calculated from these inputs.'}


def estimate_timeline(state: dict, saved_roadmap: dict, *, as_of: date | None = None,
                      observed_pace: dict | None = None) -> dict:
    """Project a broad range; an observed factor requires explicit external evidence.

    Each skill keeps its own starting source and reference range. The longest
    path anchors the shared weekly study time; paths are not added four times.
    """
    as_of = as_of or date.today()
    revision = saved_roadmap.get('revision')
    roadmap = saved_roadmap.get('document') or {}
    profile = state.get('profile') or {}
    target = profile.get('targetLevel')
    if not isinstance(revision, int) or not roadmap.get('sequence') or roadmap.get('targetLevel') != target:
        return _missing('A current saved Roadmap and target CEFR are required.', revision)
    if target not in GUIDED_HOURS:
        return _missing('The target CEFR is unknown.', revision)
    if roadmap.get('goalRevision') != (state.get('goal') or {}).get('revision'):
        return _missing('The Roadmap belongs to an earlier goal revision.', revision)
    current = profile.get('currentLevels') or {}
    measured = ((current.get('speaky') or {}).get('skills') or {})
    reported = ((current.get('selfAssessment') or {}).get('skills') or {})
    skill_paths = []
    for skill in SKILLS:
        system_level = (measured.get(skill) or {}).get('level')
        self_level = (reported.get(skill) or {}).get('level')
        start_level = system_level if system_level in GUIDED_HOURS else self_level
        if start_level not in GUIDED_HOURS:
            return _missing('A system level or explicit self-assessment is required for every Roadmap skill.', revision)
        if list(GUIDED_HOURS).index(start_level) >= list(GUIDED_HOURS).index(target):
            low, high = 0, 0
        else:
            low = max(0, GUIDED_HOURS[target][0] - GUIDED_HOURS[start_level][1])
            high = max(0, GUIDED_HOURS[target][1] - GUIDED_HOURS[start_level][0])
        skill_paths.append({'skillId': skill, 'startingLevel': start_level,
                            'source': 'system' if system_level in GUIDED_HOURS else 'self_report',
                            'referenceHours': {'earliest': low, 'latest': high}})
    if all(path['referenceHours']['latest'] == 0 for path in skill_paths):
        return _missing('The reference cannot estimate remaining work from these levels.', revision)
    intensity = profile.get('intensity') or {}
    frequency, duration = intensity.get('lessonsPerWeek'), intensity.get('lessonDurationMinutes')
    if not isinstance(frequency, int) or frequency <= 0 or not isinstance(duration, int) or duration <= 0:
        return _missing('Confirmed lessons per week and minutes per lesson are required.', revision)
    homework = profile.get('homework') or {}
    consent = homework.get('consent')
    if consent == 'no':
        homework_weekly = 0
    elif consent == 'yes':
        minutes, period = homework.get('minutes'), homework.get('period')
        if not isinstance(minutes, int) or minutes < 0 or (minutes > 0 and period not in ('day', 'week')):
            return _missing('Confirmed Homework minutes and Day/Week period are required.', revision)
        homework_weekly = minutes * (7 if period == 'day' else 1)
    else:
        return _missing('Homework consent is unknown; its weekly contribution is not assumed.', revision)
    weekly_minutes = frequency * duration + homework_weekly
    # Cumulative reference intervals yield a deliberately broad difference.
    low_hours = max(path['referenceHours']['earliest'] for path in skill_paths)
    high_hours = max(path['referenceHours']['latest'] for path in skill_paths)
    pace_note = 'No observed learning pace is available.'
    factor = 1.0
    if observed_pace is not None:
        value = observed_pace.get('guidedHourEquivalentPerStudyHour')
        if (not isinstance(value, (int, float)) or isinstance(value, bool) or
                not isfinite(value) or value <= 0 or not observed_pace.get('evidence')):
            return _missing('Observed pace needs a positive measured factor and evidence.', revision)
        factor = float(value)
        pace_note = 'Observed pace adjustment is provisional: ' + str(observed_pace['evidence'])
    low_weeks = ceil(low_hours * 60 / weekly_minutes / factor)
    high_weeks = ceil(high_hours * 60 / weekly_minutes / factor)
    dates = {'earliest': (as_of + timedelta(weeks=low_weeks)).isoformat(),
             'latest': (as_of + timedelta(weeks=high_weeks)).isoformat()}
    wanted = profile.get('deadline')
    comparison = None
    if wanted:
        try:
            deadline = date.fromisoformat(wanted)
        except (TypeError, ValueError):
            return _missing('The desired deadline is not a valid date.', revision)
        available = max(0, (deadline - as_of).days / 7)
        comparison = {'desired': wanted, 'weeksAvailable': round(available, 1),
                      'relation': 'earlier_than_range' if deadline < date.fromisoformat(dates['earliest']) else
                                  'within_range' if deadline <= date.fromisoformat(dates['latest']) else 'later_than_range',
                      'additionalStudyMinutesPerWeekForEarliest':
                          max(0, ceil(low_hours * 60 / available / factor) - weekly_minutes) if available > 0 else None}
    return {'schemaVersion': 1, 'status': 'provisional', 'roadmapRevision': revision,
            'weeks': {'earliest': low_weeks, 'latest': high_weeks}, 'dates': dates,
            'deadline': comparison, 'weeklyMinutes': {'lessons': frequency * duration,
                                                       'homework': homework_weekly},
            'referenceHours': {'earliest': low_hours, 'latest': high_hours},
            'skillPaths': skill_paths, 'source': SOURCE,
            'reasons': [f'Complete Roadmap has {len(roadmap["sequence"])} Tasks; no per-Task hours are assumed.',
                        'Each skill has a separate starting level; the longest path anchors shared study time.',
                        'Self-reported levels are provisional, not a system assessment.', pace_note],
            'uncertainty': 'Cambridge guided hours describe exam preparation, not a guaranteed Speaky completion date. '
                           'Independent Homework is counted as study time, but equivalence to guided hours is unvalidated.'}


class RoadmapTimeline:
    def __init__(self, backend):
        self.backend = backend

    async def read(self, scope: str, *, as_of: date | None = None, observed_pace: dict | None = None) -> dict:
        try:
            roadmap = (await self.backend.learning_roadmap(scope))['roadmap']
        except Exception as error:
            if getattr(error, 'code', None) == 'roadmap_not_found':
                return _missing('Analysis and Roadmap are required before a timeline is available.')
            raise
        return estimate_timeline(await self.backend.learning_state(scope), roadmap,
                                 as_of=as_of, observed_pace=observed_pace)
