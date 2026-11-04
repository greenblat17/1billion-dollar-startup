-- The launch announcement was sent before selector analytics existed. Preserve
-- callback taps while their seven-day interaction content is still available.
INSERT INTO telegram_call_events
    (event_id, chat_id, kind, occurred_at, bot_message_id, state)
SELECT 'audit:' || e.event_id, e.chat_id,
       CASE WHEN c.content = 'voice:menu' THEN 'voice_menu_clicked'
            ELSE 'voice_character_tapped' END,
       e.occurred_at, e.message_id,
       CASE WHEN c.content = 'voice:menu' THEN 'received'
            ELSE substring(c.content from 7) END
FROM interaction_events e
JOIN interaction_content c ON c.event_id = e.event_id
WHERE e.direction = 'incoming' AND e.kind = 'callback'
  AND e.chat_id IS NOT NULL
  AND e.occurred_at >= now() - interval '30 days'
  AND c.content IN ('voice:menu', 'voice:kora_h', 'voice:speaky')
ON CONFLICT (event_id) DO NOTHING;
