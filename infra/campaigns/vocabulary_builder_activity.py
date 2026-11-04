"""Read aggregate PROD activity after each recipient's announcement delivery."""

import datetime
import json
import sqlite3
import subprocess
import sys
from pathlib import Path


DB_PATH = Path("/opt/speaking-coach/campaigns/2026-11-04-vocabulary-builder.sqlite3")


def main() -> None:
    with sqlite3.connect(f"file:{DB_PATH}?mode=ro", uri=True) as database:
        recipients = database.execute(
            "SELECT chat_id, at_utc FROM delivery WHERE status = 'sent' ORDER BY chat_id",
        ).fetchall()
    if not recipients:
        raise RuntimeError("No successful announcement recipients")
    values = []
    for chat_id, sent_at in recipients:
        parsed = datetime.datetime.fromisoformat(sent_at)
        if parsed.tzinfo is None or int(chat_id) <= 0:
            raise RuntimeError("Invalid delivery record")
        values.append(f"({int(chat_id)}, TIMESTAMPTZ '{parsed.isoformat()}')")

    if sys.argv[1:] == ["--users"]:
        sql = """
WITH recipients(chat_id, sent_at) AS (VALUES %s),
active AS (
    SELECT e.chat_id, COUNT(*) AS incoming_events,
           MAX(e.occurred_at) AS last_activity_utc
    FROM interaction_events e
    JOIN recipients r ON r.chat_id = e.chat_id AND e.occurred_at > r.sent_at
    WHERE e.direction = 'incoming'
    GROUP BY e.chat_id
)
SELECT COALESCE(json_agg(json_build_object(
    'username', c.username,
    'display_name', c.display_name,
    'chat_id', a.chat_id,
    'incoming_events', a.incoming_events,
    'last_activity_utc', a.last_activity_utc
) ORDER BY a.last_activity_utc DESC), '[]'::json)::text
FROM active a
LEFT JOIN interaction_chats c ON c.chat_id = a.chat_id;
""" % ", ".join(values)
    elif not sys.argv[1:]:
        sql = """
WITH recipients(chat_id, sent_at) AS (VALUES %s),
incoming AS (
    SELECT e.chat_id, e.kind, e.occurred_at, c.content
    FROM interaction_events e
    JOIN recipients r ON r.chat_id = e.chat_id AND e.occurred_at > r.sent_at
    LEFT JOIN interaction_content c ON c.event_id = e.event_id
    WHERE e.direction = 'incoming'
),
vocab_starts AS (
    SELECT chat_id, MIN(occurred_at) AS started_at
    FROM incoming WHERE content = 'vocab:start' GROUP BY chat_id
),
outgoing AS (
    SELECT e.chat_id, e.kind, e.status, e.occurred_at
    FROM interaction_events e
    JOIN recipients r ON r.chat_id = e.chat_id AND e.occurred_at > r.sent_at
    WHERE e.direction = 'outgoing'
),
voice AS (
    SELECT v.chat_id, v.outcome
    FROM telegram_voice_attempts v
    JOIN recipients r ON r.chat_id = v.chat_id AND v.received_at > r.sent_at
),
calls AS (
    SELECT e.chat_id, e.kind
    FROM telegram_call_events e
    JOIN recipients r ON r.chat_id = e.chat_id AND e.occurred_at > r.sent_at
),
all_incoming AS (
    SELECT DISTINCT e.chat_id
    FROM interaction_events e
    WHERE e.direction = 'incoming'
      AND e.occurred_at > (SELECT MIN(sent_at) FROM recipients)
)
SELECT json_build_object(
    'as_of_utc', now(),
    'first_send_utc', (SELECT MIN(sent_at) FROM recipients),
    'last_send_utc', (SELECT MAX(sent_at) FROM recipients),
    'recipients', (SELECT COUNT(*) FROM recipients),
    'all_users_active_since_first_send', (SELECT COUNT(*) FROM all_incoming),
    'recipient_users_active', (SELECT COUNT(DISTINCT chat_id) FROM incoming),
    'recipient_incoming_events', (SELECT COUNT(*) FROM incoming),
    'recipient_first_activity_utc', (SELECT MIN(occurred_at) FROM incoming),
    'recipient_last_activity_utc', (SELECT MAX(occurred_at) FROM incoming),
    'recipient_voice_users', (SELECT COUNT(DISTINCT chat_id) FROM voice),
    'recipient_voice_messages', (SELECT COUNT(*) FROM voice),
    'recipient_voice_replies_delivered',
        (SELECT COUNT(*) FROM voice WHERE outcome = 'delivered'),
    'recipient_call_users',
        (SELECT COUNT(DISTINCT chat_id) FROM calls WHERE kind = 'call_open'),
    'recipient_calls_started',
        (SELECT COUNT(*) FROM calls WHERE kind = 'call_open'),
    'recipient_vocabulary_open_users',
        (SELECT COUNT(DISTINCT chat_id) FROM incoming
         WHERE content IN ('/words', '📚 Vocabulary Builder')
            OR content LIKE 'vocab:list:%%'),
    'recipient_vocabulary_start_users',
        (SELECT COUNT(DISTINCT chat_id) FROM incoming
         WHERE content = 'vocab:start'),
    'recipient_vocabulary_start_taps',
        (SELECT COUNT(*) FROM incoming WHERE content = 'vocab:start'),
    'recipient_vocabulary_add_users',
        (SELECT COUNT(DISTINCT chat_id) FROM incoming
         WHERE content = 'vocab:add'),
    'recipient_vocabulary_save_taps',
        (SELECT COUNT(*) FROM incoming WHERE content = 'vocab:confirm'),
    'bot_voice_accepted_after_lesson_start',
        (SELECT COUNT(*) FROM outgoing o JOIN vocab_starts s USING (chat_id)
         WHERE o.occurred_at > s.started_at AND o.kind = 'sendVoice'
           AND o.status = 'accepted'),
    'text_messages_after_lesson_start',
        (SELECT COUNT(*) FROM incoming i JOIN vocab_starts s USING (chat_id)
         WHERE i.occurred_at > s.started_at AND i.kind = 'text'),
    'recipient_bot_voices_accepted',
        (SELECT COUNT(*) FROM outgoing WHERE kind = 'sendVoice' AND status = 'accepted')
)::text;
""" % ", ".join(values)
    else:
        raise SystemExit("Usage: vocabulary_builder_activity.py [--users]")
    process = subprocess.run(
        ["docker", "exec", "-i", "postgres", "sh", "-c",
         'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -tA -v ON_ERROR_STOP=1'],
        input=sql, text=True, capture_output=True, check=False,
    )
    if process.returncode:
        raise RuntimeError(f"Read-only activity query failed: {process.stderr[-1000:]}")
    report = json.loads(process.stdout.strip())
    print(json.dumps(report, ensure_ascii=False))


if __name__ == "__main__":
    main()
