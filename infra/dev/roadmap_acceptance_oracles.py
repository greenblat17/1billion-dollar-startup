#!/usr/bin/env python3
"""Check scoped synthetic Foundation captures without claiming live pedagogy.

Usage: python3 infra/dev/roadmap_acceptance_oracles.py inputs|posttrial|reestimate result.json
The input is the sanitized result.json from foundation-check-remote.py.
"""
import json
import importlib.util
import os
import sys
from datetime import date
from pathlib import Path

def estimate_timeline(state, saved, *, as_of):
    """Use the checked-out SP-10 module; accept a source path for branch review."""
    source = Path(os.environ.get('ROADMAP_TIMELINE_SOURCE',
                                 Path(__file__).resolve().parents[2] / 'ai-service/app/roadmap_timeline.py'))
    spec = importlib.util.spec_from_file_location('acceptance_roadmap_timeline', source)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module.estimate_timeline(state, saved, as_of=as_of)


def steps(capture):
    rows = {step['name']: step for step in capture['steps']}
    for name, step in rows.items():
        assert step['httpStatus'] == 200, (name, step['httpStatus'], step['response'].get('error'))
    return {name: step['response']['result'] for name, step in rows.items()}


def inputs(capture):
    row = steps(capture)
    before = row['empty_state']
    profile = row['profile_read']['profile']
    assert row['profile_read']['revision'] == before['revision'] + 1
    assert profile['targetLevel'] == 'B2'
    assert profile['currentLevels']['speaky']['skills']['grammar']['level'] == 'B1'
    assert profile['currentLevels']['speaky']['skills']['speaking']['level'] == 'A2'
    assert profile['currentLevels']['speaky']['skills']['listening']['level'] == 'Unknown'
    assert profile['currentLevels']['selfAssessment']['skills']['listening']['level'] == 'A2'
    assert profile['intensity'] == {'lessonsPerWeek': 3, 'lessonDurationMinutes': 30}
    assert profile['homework'] == {'consent': 'yes', 'minutes': 20, 'period': 'day'}
    assert profile['deadline'] == '2026-12-18'
    assert row['known']['status'] == 'Known'
    assert row['known']['topicId'] == 'grammar-b2-third-conditional'
    assert row['roadmap_before_trial'].get('status') == 'roadmap_not_found'
    assert row['priority_before_trial'].get('status') == 'roadmap_not_found'
    assert row['tasks_before_trial']['items'] == []
    return ['SP-03 structured inputs', 'SP-08 AC-09 pre-Trial gate', 'SP-10 AC-09 pre-Trial gate']


def posttrial(capture):
    row = steps(capture)
    state = row['state']
    saved = row['roadmap']['roadmap']
    document = saved['document']
    sequence = document['sequence']
    assert document['targetLevel'] == state['profile']['targetLevel'] == 'B2'
    assert document['goalRevision'] == state['goal']['revision']
    assert document['taskCount'] == len(sequence) > 0
    assert {item['skillId'] for item in sequence} == {'speaking', 'listening', 'grammar', 'vocabulary'}
    assert all(item['cefr'] in {'A2', 'B1', 'B2'} for item in sequence)
    assert all(item['knowledge'] != 'Unknown' or item['provisional'] for item in sequence)
    assert all(item['reason'] and item['orderReason'] for item in sequence)
    assert any(item['goalFocus'] for item in sequence)
    assert all(item['topicId'] != 'grammar-b2-third-conditional' for item in sequence), \
        'A Known Topic remained on a fresh route, even if relabeled'
    assumptions = {item['skillId']: item for item in document['assumptions']}
    assert set(assumptions) == {'speaking', 'listening', 'grammar', 'vocabulary'}
    assert len({item['startingLevel'] for item in assumptions.values()}) > 1, \
        'This capture does not exercise different Skill starting levels'
    for skill, item in assumptions.items():
        system = state['profile']['currentLevels']['speaky']['skills'][skill]['level']
        reported = state['profile']['currentLevels']['selfAssessment']['skills'][skill]['level']
        expected_source = 'system' if system != 'Unknown' else 'self_report' if reported != 'Unknown' else 'unknown'
        assert item['source'] == expected_source
        assert item['startingLevel'] == (system if expected_source == 'system' else
                                         reported if expected_source == 'self_report' else 'Unknown')
    ids = [item['taskId'] for item in sequence]
    assert all(ids) and len(ids) == len(set(ids))
    assert len({(item['topicId'], item['problemId']) for item in sequence}) == len(sequence)
    tasks = sum((row[f'tasks_{offset}']['items'] for offset in (0, 50, 100, 150)), [])
    assert row['tasks_150']['nextOffset'] is None, 'Task pagination exceeded scenario coverage'
    by_id = {task['id']: task for task in tasks}
    assert len(tasks) == len(by_id) and set(ids) <= set(by_id)
    assert all(by_id[item['taskId']]['progress'] == 0 for item in sequence)
    priority = row['priority']
    assert priority['roadmapRevision'] == saved['revision']
    assert priority['stale'] is False
    decision = priority['priority']['document']
    ordered = [item['taskId'] for item in decision['orderedTasks']]
    assert set(ids) <= set(ordered) and len(ordered) == len(set(ordered))
    assert decision['nextTaskId'] in ordered
    evidence = {item['taskId']: item['evidence'] for item in decision['orderedTasks']}
    for item in sequence:
        if item['knowledge'] in {'Unknown', 'Presumed Not Known'}:
            assert evidence[item['taskId']] == 'hypothesis'
        elif item['knowledge'] == 'Not Known' and item['provisional']:
            assert evidence[item['taskId']] == 'topic_confirmed_problem_hypothesis'
    timeline = estimate_timeline(state, saved, as_of=date.today())
    assert timeline['roadmapRevision'] == saved['revision']
    assert timeline['status'] == 'provisional', timeline['reasons']
    assert timeline['weeks']['earliest'] <= timeline['weeks']['latest']
    assert {path['skillId'] for path in timeline['skillPaths']} == {
        'speaking', 'listening', 'grammar', 'vocabulary'}
    assert all(path['source'] in {'system', 'self_report'} for path in timeline['skillPaths'])
    assert timeline['weeklyMinutes']['lessons'] == (state['profile']['intensity']['lessonsPerWeek'] *
                                                    state['profile']['intensity']['lessonDurationMinutes'])
    assert timeline['deadline']['relation'] == 'earlier_than_range'
    assert timeline['source'].startswith('https://support.cambridgeenglish.org/')
    assert 'not a guaranteed' in timeline['uncertainty']
    return ['SP-08 AC-01/02/03/04/06/07/08/09', 'SP-09 AC-01/02/08',
            'SP-10 AC-01/03/04/06/10 (conditional on complete inputs)']


def existing(capture):
    """Read-only check for a previously used agent_test Trial UUID."""
    row = steps(capture)
    saved = row['roadmap'].get('roadmap')
    assert saved is not None, 'No Roadmap for this agent_test UUID; check identity and Trial revision'
    state = row['state']
    document = saved['document']
    sequence = document['sequence']
    assert sequence and document['taskCount'] == len(sequence)
    assert document['targetLevel'] == state['profile']['targetLevel']
    assert document['goalRevision'] == state['goal']['revision']
    ids = [item['taskId'] for item in sequence]
    assert all(ids) and len(ids) == len(set(ids))
    assert len({(item['topicId'], item['problemId']) for item in sequence}) == len(sequence)
    assert all(item['knowledge'] != 'Unknown' or item['provisional'] for item in sequence)
    assert row['tasks_150']['nextOffset'] is None, 'Task pagination exceeded capture coverage'
    tasks = sum((row[f'tasks_{offset}']['items'] for offset in (0, 50, 100, 150)), [])
    by_id = {task['id']: task for task in tasks}
    assert len(tasks) == len(by_id) and set(ids) <= set(by_id)
    priority = row.get('priority')
    if priority is not None and priority.get('status') != 'roadmap_not_found':
        assert priority['roadmapRevision'] == saved['revision']
        if priority['priority'] is not None and not priority['stale']:
            decision = priority['priority']['document']
            assert decision['nextTaskId'] in {item['taskId'] for item in decision['orderedTasks']}
    timeline = estimate_timeline(state, saved, as_of=date.today())
    assert timeline['roadmapRevision'] == saved['revision']
    assert timeline['status'] in {'provisional', 'insufficient_data'}
    return ['Existing synthetic Roadmap/Task linkage',
            'priority presence or stale state' if priority is not None else 'priority not captured',
            f"SP-10 projection status: {timeline['status']}"]


def reestimate(capture):
    row = steps(capture)
    base = row['roadmap_before']['roadmap']
    no = row['roadmap_without_homework']['roadmap']
    four = row['roadmap_four_lessons']['roadmap']
    assert base == no == four
    assert row['priority_before'] == row['priority_without_homework'] == row['priority_four_lessons']
    original = row['state_before']
    without = row['state_without_homework']
    increased = row['state_four_lessons']
    assert original['profile']['homework']['consent'] == 'yes'
    assert without['profile']['homework']['consent'] == increased['profile']['homework']['consent'] == 'no'
    assert without['profile']['homework']['minutes'] == original['profile']['homework']['minutes']
    assert increased['profile']['intensity']['lessonsPerWeek'] == 4
    assert original['goal'] == without['goal'] == increased['goal']
    old = estimate_timeline(original, base, as_of=date.today())
    no_homework = estimate_timeline(without, no, as_of=date.today())
    four_lessons = estimate_timeline(increased, four, as_of=date.today())
    assert all(item['status'] == 'provisional' for item in (old, no_homework, four_lessons))
    assert no_homework['weeklyMinutes']['homework'] == four_lessons['weeklyMinutes']['homework'] == 0
    assert no_homework['weeks']['latest'] > old['weeks']['latest']
    assert four_lessons['weeks']['latest'] < no_homework['weeks']['latest']
    assert row['tasks_before_150']['nextOffset'] is None and row['tasks_after_150']['nextOffset'] is None
    before_rows = sum((row[f'tasks_before_{offset}']['items'] for offset in (0, 50, 100, 150)), [])
    after_rows = sum((row[f'tasks_after_{offset}']['items'] for offset in (0, 50, 100, 150)), [])
    before_tasks = {task['id']: (task['lifecycle'], task['progress']) for task in before_rows}
    after_tasks = {task['id']: (task['lifecycle'], task['progress']) for task in after_rows}
    assert before_tasks == after_tasks
    return ['SP-10 AC-02/06/07/10', 'SP-09 AC-08 (unchanged priority and task progress)']


CHECKS = {'inputs': inputs, 'posttrial': posttrial, 'reestimate': reestimate, 'existing': existing}


def main():
    if len(sys.argv) != 3 or sys.argv[1] not in CHECKS:
        raise SystemExit('Usage: roadmap_acceptance_oracles.py inputs|posttrial|reestimate|existing result.json')
    capture = json.loads(Path(sys.argv[2]).read_text())
    passed = CHECKS[sys.argv[1]](capture)
    print(json.dumps({'scenario': sys.argv[1], 'checked': passed, 'evidence': 'scoped synthetic API capture only'},
                     ensure_ascii=False))


if __name__ == '__main__':
    main()
