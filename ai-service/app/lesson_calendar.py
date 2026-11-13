"""Calendar export from an authenticated, current Lesson; no calendar account access."""
from datetime import datetime, timedelta, timezone
from urllib.parse import urlencode


def _stamp(value):
    return value.astimezone(timezone.utc).strftime('%Y%m%dT%H%M%SZ')


def _escape(value):
    return str(value).replace('\\', '\\\\').replace('\r', '').replace('\n', '\\n').replace(';', '\\;').replace(',', '\\,')


def _fold(line):
    parts, current = [], ''
    for char in line:
        if len((current + char).encode('utf-8')) > 75:
            parts.append(current)
            current = ' '
        current += char
    return '\r\n'.join(parts + [current])


def calendar_export(lesson):
    start = datetime.fromisoformat(lesson['starts_at'])
    end = start + timedelta(minutes=lesson['duration_minutes'])
    title = 'Пробное занятие с Сашей' if lesson['kind'] == 'trial' else 'Английский с Сашей'
    description = ('Подключение: кнопка «Открыть урок» в чате с Сашей в Telegram. '
                   'Перенести или отменить занятие можно сообщением Саше. '
                   'После переноса обновите событие в своём календаре; автоматической синхронизации нет.')
    lines = ['BEGIN:VCALENDAR', 'VERSION:2.0', 'PRODID:-//Speaky//Lessons//RU', 'CALSCALE:GREGORIAN',
             'BEGIN:VEVENT', f"UID:{_escape(lesson['id'])}@speaky", f"SEQUENCE:{int(lesson['version'])}",
             f"DTSTAMP:{_stamp(datetime.fromisoformat(lesson['updated_at']))}",
             f'DTSTART:{_stamp(start)}', f'DTEND:{_stamp(end)}', f'SUMMARY:{_escape(title)}',
             'LOCATION:Speaky · Telegram', f'DESCRIPTION:{_escape(description)}',
             'STATUS:' + ('CANCELLED' if lesson['status'] == 'cancelled' else 'CONFIRMED'),
             'END:VEVENT', 'END:VCALENDAR']
    google = None if lesson['status'] != 'scheduled' else 'https://calendar.google.com/calendar/render?' + urlencode({
        'action': 'TEMPLATE', 'text': title, 'dates': f'{_stamp(start)}/{_stamp(end)}',
        'details': description, 'location': 'Speaky · Telegram'})
    return {'lesson': lesson, 'ics': '\r\n'.join(_fold(line) for line in lines) + '\r\n',
            'googleUrl': google, 'filename': 'speaky-lesson.ics'}
