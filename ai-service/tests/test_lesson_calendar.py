from urllib.parse import urlparse, parse_qs
from app.lesson_calendar import calendar_export
from app.foundation_tutor import booking_label, booking_projection


def lesson(**changes):
    return dict(id='lesson-1', student_id='student', starts_at='2026-11-14T16:00:00+00:00',
                updated_at='2026-11-13T10:00:00+00:00', timezone='Europe/Moscow',
                duration_minutes=15, kind='trial', version=1, status='scheduled', launch_mode='executable', **changes)


def test_calendar_has_correct_instants_and_roundtrippable_utf8_folding():
    export = calendar_export(lesson())
    query = parse_qs(urlparse(export['googleUrl']).query)
    assert query['dates'] == ['20261114T160000Z/20261114T161500Z']
    assert query['text'] == ['Пробное занятие с Сашей']
    assert 'UID:lesson-1@speaky\r\n' in export['ics']
    assert all(len(line.encode()) <= 75 for line in export['ics'].split('\r\n'))
    assert 'автоматической синхронизации нет.' in export['ics'].replace('\r\n ', '')


def test_reschedule_keeps_uid_increments_sequence_and_cancellation_removes_google_link():
    before = lesson()
    after = {**before, 'starts_at': '2026-11-15T08:00:00+00:00', 'version': 2}
    export = calendar_export(after)
    assert 'UID:lesson-1@speaky' in export['ics']
    assert 'SEQUENCE:2' in export['ics']
    assert 'DTSTART:20261115T080000Z' in export['ics']
    cancelled = calendar_export({**after, 'status': 'cancelled', 'version': 3})
    assert cancelled['googleUrl'] is None
    assert 'STATUS:CANCELLED' in cancelled['ics']


def test_confirmation_uses_actual_date_timezone_and_duration():
    text = booking_label(booking_projection(lesson()))
    assert '14 ноября 2026, суббота' in text
    assert '19:00 · Europe/Moscow · 15 минут' in text
    assert 'бесплатно' in text
    assert 'недоступен' not in text


def test_calendar_api_authenticates_through_backend_and_uses_current_saved_time():
    from fastapi import FastAPI
    from fastapi.testclient import TestClient
    from types import SimpleNamespace
    from unittest.mock import AsyncMock
    from app.trial_api import install_trial_api
    backend = SimpleNamespace(_request=AsyncMock(return_value={'lesson': lesson()}))
    app = FastAPI()
    install_trial_api(app, backend, None, None, None, None, None)
    response = TestClient(app).post('/miniapp/api', json={'action': 'calendar', 'lessonId': 'lesson-1', 'initData': 'signed'})
    assert response.status_code == 200
    assert response.json()['lesson']['id'] == 'lesson-1'
    assert 'DTSTART:20261114T160000Z' in response.json()['ics']
    backend._request.assert_awaited_once_with('POST', '/trial/access', '', json={
        'action': 'calendar', 'lessonId': 'lesson-1', 'initData': 'signed'})
