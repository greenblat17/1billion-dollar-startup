"""SP-10 contract tests with fixtures; these do not validate real learner timelines."""
from copy import deepcopy
from datetime import date

import pytest

from app.roadmap_timeline import RoadmapTimeline, estimate_timeline


def inputs():
    state = {'goal': {'revision': 3}, 'profile': {
        'targetLevel': 'B2', 'deadline': '2026-12-01',
        'currentLevels': {'speaky': {'skills': {name: {'level': 'B1'} for name in
            ('speaking', 'listening', 'grammar', 'vocabulary')}}},
        'intensity': {'lessonsPerWeek': 3, 'lessonDurationMinutes': 30},
        'homework': {'consent': 'yes', 'minutes': 20, 'period': 'day'}}}
    roadmap = {'revision': 2, 'document': {'targetLevel': 'B2', 'goalRevision': 3,
        'sequence': [{'taskId': 't1'}, {'taskId': 't2'}], 'history': [{'taskId': 'old'}]}}
    return state, roadmap


def estimate(state=None, roadmap=None, **kwargs):
    original_state, original_roadmap = inputs()
    return estimate_timeline(state or original_state, roadmap or original_roadmap,
                             as_of=date(2026, 11, 17), **kwargs)


def test_range_deadline_and_day_normalization_use_complete_route_without_mutation():
    state, roadmap = inputs()
    before = deepcopy(roadmap)
    result = estimate(state, roadmap)
    assert result['status'] == 'provisional'
    assert result['weeklyMinutes'] == {'lessons': 90, 'homework': 140}
    assert result['referenceHours'] == {'earliest': 100, 'latest': 250}
    assert result['weeks']['earliest'] < result['weeks']['latest']
    assert result['deadline']['relation'] == 'earlier_than_range'
    assert result['deadline']['additionalStudyMinutesPerWeekForEarliest'] > 0
    assert roadmap == before


def test_homework_no_excludes_stale_minutes_but_keeps_route_and_history():
    state, roadmap = inputs()
    state['profile']['homework']['consent'] = 'no'
    before = deepcopy(roadmap)
    without = estimate(state, roadmap)
    assert without['weeklyMinutes']['homework'] == 0
    assert without['weeks']['latest'] > estimate()['weeks']['latest']
    assert roadmap == before


def test_different_skill_levels_remain_visible_without_counting_shared_hours_four_times():
    state, roadmap = inputs()
    state['profile']['currentLevels']['speaky']['skills']['grammar']['level'] = 'A2'
    state['profile']['currentLevels']['speaky']['skills']['speaking']['level'] = 'B2'
    result = estimate(state, roadmap)
    paths = {item['skillId']: item for item in result['skillPaths']}
    assert paths['grammar']['referenceHours'] == {'earliest': 300, 'latest': 420}
    assert paths['speaking']['referenceHours'] == {'earliest': 0, 'latest': 0}
    assert result['referenceHours'] == {'earliest': 300, 'latest': 420}


def test_trial_listening_self_report_is_explicitly_provisional():
    state, roadmap = inputs()
    state['profile']['currentLevels']['speaky']['skills']['listening']['level'] = 'Unknown'
    state['profile']['currentLevels']['selfAssessment'] = {
        'skills': {'listening': {'level': 'A2'}}}
    result = estimate(state, roadmap)
    assert result['status'] == 'provisional'
    listening = next(item for item in result['skillPaths'] if item['skillId'] == 'listening')
    assert listening['startingLevel'] == 'A2'
    assert listening['source'] == 'self_report'


def test_frequency_duration_weekly_homework_and_observed_pace_recalculate():
    state, roadmap = inputs()
    state['profile']['homework'] = {'consent': 'yes', 'minutes': 120, 'period': 'week'}
    initial = estimate(state, roadmap)
    assert initial['weeklyMinutes']['homework'] == 120
    state['profile']['intensity']['lessonsPerWeek'] = 4
    state['profile']['intensity']['lessonDurationMinutes'] = 45
    faster = estimate(state, roadmap)
    assert faster['weeks']['latest'] < initial['weeks']['latest']
    observed = estimate(state, roadmap, observed_pace={
        'guidedHourEquivalentPerStudyHour': 1.2, 'evidence': 'observed study sessions'})
    assert observed['weeks']['latest'] < faster['weeks']['latest']


@pytest.mark.parametrize('change', [
    lambda s: s['profile']['intensity'].update(lessonDurationMinutes=None),
    lambda s: s['profile']['homework'].update(consent='unknown'),
    lambda s: s['profile']['homework'].update(period=None),
    lambda s: s['profile'].update(targetLevel='Unknown'),
    lambda s: s['profile']['currentLevels']['speaky']['skills']['speaking'].update(level='Unknown'),
])
def test_missing_structured_inputs_never_get_a_numeric_forecast(change):
    state, roadmap = inputs()
    change(state)
    result = estimate(state, roadmap)
    assert result['status'] == 'insufficient_data'
    assert result['weeks'] is None


@pytest.mark.asyncio
async def test_trial_without_roadmap_has_no_timeline():
    class MissingRoadmap:
        async def learning_roadmap(self, scope):
            error = RuntimeError('roadmap_not_found')
            error.code = 'roadmap_not_found'
            raise error

    result = await RoadmapTimeline(MissingRoadmap()).read('scope')
    assert result['weeks'] is None
    assert result['status'] == 'insufficient_data'


@pytest.mark.asyncio
async def test_sp11_read_adapter_consumes_saved_roadmap_and_live_profile():
    state, roadmap = inputs()

    class Backend:
        async def learning_roadmap(self, scope):
            assert scope == 'student-scope'
            return {'schemaVersion': 1, 'roadmap': roadmap}

        async def learning_state(self, scope):
            assert scope == 'student-scope'
            return state

    result = await RoadmapTimeline(Backend()).read('student-scope', as_of=date(2026, 11, 17))
    assert result['roadmapRevision'] == 2
    assert result['status'] == 'provisional'
