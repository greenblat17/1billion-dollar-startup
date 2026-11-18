"""Local tests of the DEV oracle, not DEV acceptance evidence."""
import importlib.util
import json
import unittest
from copy import deepcopy
from pathlib import Path


HERE = Path(__file__).parent
SPEC = importlib.util.spec_from_file_location('roadmap_acceptance_oracles', HERE / 'roadmap_acceptance_oracles.py')
oracle = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(oracle)


def capture(**values):
    return {'steps': [{'name': name, 'httpStatus': 200, 'response': {'result': value}}
                      for name, value in values.items()]}


def state(consent='yes', frequency=3):
    skills = {'speaking': {'level': 'A2'}, 'listening': {'level': 'Unknown'},
              'grammar': {'level': 'B1'}, 'vocabulary': {'level': 'A2'}}
    reported = {name: {'level': 'A2'} for name in skills}
    return {'revision': 3, 'goal': {'revision': 1}, 'profile': {
        'targetLevel': 'B2', 'deadline': '2026-12-18',
        'currentLevels': {'speaky': {'skills': skills}, 'selfAssessment': {'skills': reported}},
        'intensity': {'lessonsPerWeek': frequency, 'lessonDurationMinutes': 30},
        'homework': {'consent': consent, 'minutes': 20, 'period': 'day'}}}


def roadmap():
    return {'revision': 1, 'document': {'targetLevel': 'B2', 'goalRevision': 1, 'taskCount': 4,
        'assumptions': [{'skillId': name, 'startingLevel': 'B1' if name == 'grammar' else 'A2',
                         'source': 'self_report' if name == 'listening' else 'system'}
                        for name in ('speaking', 'listening', 'grammar', 'vocabulary')],
        'sequence': [{'taskId': name, 'topicId': name, 'problemId': 'p-' + name,
                      'skillId': name, 'cefr': 'B2', 'knowledge': 'Unknown',
                      'provisional': True, 'reason': 'hypothesis', 'orderReason': 'personal order',
                      'goalFocus': name == 'speaking'}
                     for name in ('speaking', 'listening', 'grammar', 'vocabulary')]}}


def priority():
    names = ('speaking', 'listening', 'grammar', 'vocabulary')
    return {'roadmapRevision': 1, 'stale': False, 'priority': {'document': {
        'orderedTasks': [{'taskId': name, 'evidence': 'hypothesis', 'reason': 'priority'} for name in names],
        'nextTaskId': 'speaking'}}}


def task_pages(prefix):
    rows = [{'id': name, 'lifecycle': 'Identified', 'progress': 0}
            for name in ('speaking', 'listening', 'grammar', 'vocabulary')]
    return {f'{prefix}_{offset}': {'items': rows if offset == 0 else [], 'nextOffset': None}
            for offset in (0, 50, 100, 150)}


class RoadmapOracleTest(unittest.TestCase):
    def test_scenarios_are_runner_compatible_and_bounded(self):
        for name in ('inputs', 'self-report-inputs', 'posttrial', 'reestimate', 'existing-read', 'existing-core'):
            path = HERE / 'scenarios' / f'foundation-roadmap-{name}.json'
            steps = json.loads(path.read_text())['steps']
            self.assertLessEqual(len(steps), 40)
            self.assertEqual(len(steps), len({step['name'] for step in steps}))
            self.assertTrue(all(set(step) == {'name', 'request'} for step in steps))

    def test_new_trial_fixture_has_no_synthetic_measurement_or_known_topic(self):
        path = HERE / 'scenarios' / 'foundation-roadmap-self-report-inputs.json'
        steps = json.loads(path.read_text())['steps']
        requests = [step['request'] for step in steps]
        writes = [request for request in requests if request['kind'] == 'command']
        self.assertEqual([request['action'] for request in writes], ['learning.update'])
        patch = writes[0]['args']['patch']
        self.assertEqual(set(patch['currentLevels']), {'selfAssessment'})
        self.assertEqual(set(patch['currentLevels']['selfAssessment']['skills']),
                         {'speaking', 'listening', 'grammar', 'vocabulary'})
        self.assertEqual(writes[0]['args']['sources'], {'$ref': 'source.response.result.sources'})
        original = json.loads((HERE / 'scenarios' / 'foundation-roadmap-inputs.json').read_text())['steps']
        measured = next(step['request']['args']['patch']['currentLevels']['speaky'] for step in original
                        if step['name'] == 'profile')
        self.assertEqual(measured['skills']['grammar']['level'], 'B1')
        self.assertEqual(measured['skills']['speaking']['level'], 'A2')

    def test_self_report_input_oracle_rejects_synthetic_speaky_provenance(self):
        profile = {'targetLevel': 'B2', 'deadline': '2026-12-18',
                   'currentLevels': {'speaky': {'skills': {skill: {'level': 'Unknown'} for skill in
                       ('speaking', 'listening', 'grammar', 'vocabulary')}},
                       'selfAssessment': {'skills': {skill: {'level': 'A2'} for skill in
                           ('speaking', 'listening', 'grammar', 'vocabulary')}}},
                   'intensity': {'lessonsPerWeek': 3, 'lessonDurationMinutes': 30},
                   'homework': {'consent': 'yes', 'minutes': 20, 'period': 'day'}}
        provenance = {f'currentLevels.selfAssessment.skills.{skill}.level':
                      {'component': 'foundation-acceptance-self-report'} for skill in
                      ('speaking', 'listening', 'grammar', 'vocabulary')}
        rows = {'empty_state': {'revision': 0}, 'profile_read': {'revision': 1,
                'goal': {'content': {'value': 'Use English for work and reach B2',
                                    'sources': [{'id': 'synthetic-event'}]}},
                'profile': profile, 'provenance': provenance},
                'roadmap_before_trial': {'status': 'roadmap_not_found'},
                'priority_before_trial': {'status': 'roadmap_not_found'},
                'tasks_before_trial': {'items': []}}
        self.assertTrue(oracle.self_report_inputs(capture(**rows)))
        provenance['currentLevels.speaky.skills.grammar.level'] = {'component': 'foundation-acceptance-self-report'}
        with self.assertRaises(AssertionError):
            oracle.self_report_inputs(capture(**rows))

    def test_input_oracle_requires_known_and_pretrial_gate(self):
        profile = {'targetLevel': 'B2', 'deadline': '2026-12-18',
                   'currentLevels': {
                       'speaky': {'skills': {'grammar': {'level': 'B1'}, 'speaking': {'level': 'A2'},
                                            'listening': {'level': 'Unknown'}}},
                       'selfAssessment': {'skills': {'listening': {'level': 'A2'}}}},
                   'intensity': {'lessonsPerWeek': 3, 'lessonDurationMinutes': 30},
                   'homework': {'consent': 'yes', 'minutes': 20, 'period': 'day'}}
        rows = {'empty_state': {'revision': 0}, 'profile_read': {'revision': 1, 'profile': profile},
                'known': {'status': 'Known', 'topicId': 'grammar-b2-third-conditional'},
                'roadmap_before_trial': {'status': 'roadmap_not_found'},
                'priority_before_trial': {'status': 'roadmap_not_found'},
                'tasks_before_trial': {'items': []}}
        self.assertTrue(oracle.inputs(capture(**rows)))
        rows['roadmap_before_trial'] = {'roadmap': roadmap()}
        with self.assertRaises(AssertionError):
            oracle.inputs(capture(**rows))

    def test_posttrial_oracle_checks_route_priority_tasks_and_provisional_time(self):
        rows = {'state': state(), 'roadmap': {'roadmap': roadmap()}, 'priority': priority()}
        rows.update(task_pages('tasks'))
        result = capture(**rows)
        self.assertTrue(oracle.posttrial(result))
        broken = deepcopy(result)
        broken['steps'][1]['response']['result']['roadmap']['document']['sequence'][1]['taskId'] = 'speaking'
        with self.assertRaises(AssertionError):
            oracle.posttrial(broken)
        mislabeled = deepcopy(result)
        mislabeled['steps'][1]['response']['result']['roadmap']['document']['sequence'][0]['topicId'] = 'grammar-b2-third-conditional'
        mislabeled['steps'][1]['response']['result']['roadmap']['document']['sequence'][0]['knowledge'] = 'Unknown'
        with self.assertRaises(AssertionError):
            oracle.posttrial(mislabeled)

    def test_self_report_posttrial_does_not_claim_mixed_measured_or_known(self):
        learning = state()
        skills = ('speaking', 'listening', 'grammar', 'vocabulary')
        learning['profile']['currentLevels']['speaky']['skills'] = {
            skill: {'level': 'Unknown'} for skill in skills}
        learning['provenance'] = {}
        saved = roadmap()
        saved['document']['assumptions'] = [
            {'skillId': skill, 'startingLevel': 'A2', 'source': 'self_report'} for skill in skills]
        rows = {'state': learning, 'roadmap': {'roadmap': saved}, 'priority': priority()}
        rows.update(task_pages('tasks'))
        result = capture(**rows)
        self.assertTrue(oracle.self_report_posttrial(result))
        learning['provenance']['currentLevels.speaky.skills.grammar.level'] = {
            'component': 'foundation-acceptance-synthetic'}
        with self.assertRaises(AssertionError):
            oracle.self_report_posttrial(result)

    def test_existing_read_accepts_any_valid_synthetic_goal(self):
        rows = {'state': state(), 'roadmap': {'roadmap': roadmap()}, 'priority': priority(),
                'jobs': {'jobs': []}}
        rows.update(task_pages('tasks'))
        self.assertTrue(oracle.existing(capture(**rows)))
        rows.pop('priority')
        self.assertTrue(oracle.existing(capture(**rows)))

    def test_reestimate_oracle_detects_progress_loss(self):
        base = state()
        no = state(consent='no')
        four = state(consent='no', frequency=4)
        route = {'roadmap': roadmap()}
        rows = {'state_before': base, 'roadmap_before': route, 'priority_before': priority(),
                'state_without_homework': no, 'roadmap_without_homework': route,
                'priority_without_homework': priority(), 'state_four_lessons': four,
                'roadmap_four_lessons': route, 'priority_four_lessons': priority()}
        rows.update(task_pages('tasks_before'))
        rows.update(task_pages('tasks_after'))
        result = capture(**rows)
        self.assertTrue(oracle.reestimate(result))
        broken = deepcopy(result)
        next(step for step in broken['steps'] if step['name'] == 'tasks_after_0')['response']['result']['items'][0]['progress'] = 10
        with self.assertRaises(AssertionError):
            oracle.reestimate(broken)


if __name__ == '__main__':
    unittest.main()
