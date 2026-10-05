CREATE TABLE interaction_chats (
    chat_id BIGINT PRIMARY KEY,
    username TEXT,
    display_name TEXT,
    last_incoming_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX interaction_chats_recent_idx ON interaction_chats (last_incoming_at DESC, chat_id DESC);

INSERT INTO interaction_chats (chat_id, last_incoming_at)
SELECT chat_id, MAX(occurred_at)
FROM interaction_events
WHERE chat_id IS NOT NULL AND direction = 'incoming' AND received_at > now() - interval '30 days'
GROUP BY chat_id;

UPDATE interaction_chats AS chat
SET username = voice.username
FROM (
    SELECT DISTINCT ON (chat_id) chat_id, username
    FROM telegram_voice_attempts
    WHERE username <> '' AND received_at > now() - interval '30 days'
    ORDER BY chat_id, received_at DESC
) AS voice
WHERE chat.chat_id = voice.chat_id;
