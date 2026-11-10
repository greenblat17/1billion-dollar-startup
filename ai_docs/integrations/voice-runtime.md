# Сохранённый voice runtime

Саша использует отдельный текстовый prompt. Этот документ объясняет работающий voice-код и его совместимость.

## Персона, режим, история

`characters.py` выбирает personality, `conversation.py` отделяет её от режима free/situation.
Kora H использует `kora_personality.py`, `kora_h_prompts.py`, KoraHModel и отдельную speech-реализацию.
Текущий OpenRouter wiring задаёт `KORA_MODEL`; озвучка — `KORA_TTS_MODEL`/`KORA_TTS_VOICE`.
GeminiKoraSpeech получает PCM через OpenRouter, конвертирует его в OGG и применяет `/speed`.
Показанный текст — текст Sonnet; Gemini не возвращает transcript для независимой проверки произнесённых слов.
HumeSpeech сохранён для других voice-экспериментов, но не обслуживает Telegram Kora H.
Free history объединяется под `free:v1`, с миграционными маркерами и защитой от повторного хода в `dialogue.py`.
Это объединение voice-персон, не синхронизация с `tutor:v1`.
Situation хранит fictional context в call и не обновляет personal facts или free history.
`scenarioKind=job|manager|custom` и custom description передаются в `/internal/calls/start`.
Временное ожидание custom text в Ktor теряется при рестарте; сохранённый call сохраняет свой scenario.

## Голос, время и провайдеры

`/speed` хранит независимый `speech-speed:{sessionId}`: 0.8, 0.9 или 1.0.
Direct Deepgram использует этот параметр; обычный OpenRouter TTS его игнорирует.
`TTS_PROVIDER`, `TTS_OUTPUT_FORMAT` и media type определяют MP3/OGG; Kotlin выбирает соответствующее расширение.
Причина поддержки двух форматов — совместимость клиентов и устранение лишнего перекодирования для direct MP3.
Порядок переключения и rollback — в [deploy](../deploy.md); API — в [clip contract](2026-09-18-clip-session-api.md).

Речевое время и длительность записи — разные поля. STT usage/cost использует длительность записи.
VAD выполняется параллельно STT; после STT ожидание ограничено 0.5 секунды.
При пустом STT счётное время равно нулю; при сбое VAD используется запись с причиной fallback.
Метод фиксируется для московского дня calls, чтобы цель не меняла знаменатель посреди дня.
Старые агрегаты не пересчитываются из отсутствующего аудио; источники и версии учитываются в аналитике.
Проверять `speech_time.py`, `pipeline.py`, `calls.py` и [технический контракт](2026-10-05-telegram-call-dashboard.md).

## Review

Менее 30 счётных секунд дают `reviewKind=insufficient` без изменения assessment.
При 30 и более секундах sufficiency проверяет модель; неизвестность не превращается в уверенную оценку отсутствия речи.
Новый full review с `presentationVersion=2` показывает Grammar → Vocabulary → Fluency → Level.
Старые cached payload без версии продолжают прежний renderer: это причина оставшихся веток UI.
Insufficient review не показывает skill-карусель и feedback survey; настоящая ошибка review сохраняет Retry.
Новый full review не генерирует recap; старое поле сохраняется для чтения кеша.
[Calls](2026-09-30-telegram-calls.md), [review enrichment](2026-10-21-vocabulary-assessment-workflow.md),
[feedback](2026-10-05-first-call-feedback.md) описывают детали.

## Доказательства и ограничения

В замере 2026-10-01 по три запроса на текст direct Deepgram MP3 + OGG дал медианы 1.17/2.78 с
для 44/160 символов; OpenRouter MP3 + OGG — 1.87/4.50 с; direct OGG — 1.55/3.92 с.
Это причина эксперимента с direct provider, не текущая end-to-end latency Telegram.
Тогда 196 AI tests и `:server:test` прошли, живая Telegram latency-приёмка оставалась открытой.
Другие реальные model/voice измерения сохранены в `researches/` и нагрузочных отчётах.
