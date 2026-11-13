# Голосовые звонки, память и review

Справочник сохранённого **legacy-кода**. Его routes/workers не подключены к shipping roots Speaky.
Данные Redis и прежние контракты сохраняются для совместимости, не для активного tutor runtime.
Текущие уроки, память и прогресс описаны в [архитектуре](../../architecture.md),
границы поставки — в [статусе](../status.md). Упоминания «текущего» ниже относятся к legacy-реализации.

- [Clip / session HTTP contract](#clip--session-http-contract)
- [Сохранённый voice runtime](#сохранённый-voice-runtime)
- [Telegram practice calls](#telegram-practice-calls)
- [Память сохранённых звонков](#память-сохранённых-звонков)
- [Коррекции речи в сохранённых calls](#коррекции-речи-в-сохранённых-calls)
- [Vocabulary assessment workflow](#vocabulary-assessment-workflow)
- [Telegram progress profile](#telegram-progress-profile)
- [Feedback завершённого call](#feedback-завершённого-call)

## Clip / session HTTP contract

Canonical DTOs: `cmp/server/.../ai/ClipDtos.kt`. Python: `ai-service/app/main.py`, job payload `jobs.py`. Ktor **does not** expose these paths publicly. Telegram uses `HttpClipClient` server-side. Every `/v1/*` call needs header `X-Internal-Token` matching `AI_INTERNAL_TOKEN` on both hosts. `/health` is open.

Base URL examples: local uvicorn `http://127.0.0.1:8090`, two-host DEV/prod `http://<ai-server>:$AI_SERVICE_PORT`.

### `POST /v1/sessions` → 201

JSON optional: `{ "sessionId": "tg-123" }`. Empty/missing body → new UUID. Same id again is **get-or-create** (Redis: refresh TTL; memory: keep live session).

Response:

```json
{ "sessionId": "tg-123", "greeting": { "text": "Hey! 👋\nI'm Speaky..." } }
```

Telegram always sends the chat-scoped id so Redis history sticks to one chat.

### `GET /v1/sessions/{sessionId}/greeting/audio` → 200 `audio/ogg` or `audio/mpeg`, or 404

Synthesizes `GREETING_VOICE_TEXT` once per selected speed (cached in process).

### `POST /v1/clips` multipart → 202 `{ "jobId" }`

Fields: `sessionId` (form), `audio` (file). 404 unknown session, 400 empty audio.

### Speech speed (internal, token required)

`GET /internal/speech-speed/{sessionId}` returns `{ "speed": 0.9 }` when no chat override exists. `POST /internal/speech-speed` accepts `{ "sessionId": "tg-123", "speed": 0.8 }` and returns the selected speed. Only `0.8`, `0.9`, and `1.0` are accepted. The override is stored without TTL in Redis as `speech-speed:{sessionId}` (in memory without Redis). It controls future synthesis by direct Deepgram for this session; OpenRouter ignores it. Cached greeting audio are separated by speed. Deploy AI before Ktor so `/speed` can call these endpoints.

### `GET /v1/clips/{jobId}` → 200 or 404

`status`: `pending` | `ok` | `error`.

The CMP server polls a pending job once every 1.5 seconds. The AI job and HTTP response formats are unchanged.

On `ok`, Kotlin reads `result.corrections` (falls back to `result.notes` when `corrections` is empty or absent), `result.transcript`, fallback top-level `transcript`. Extra fields `replyText`, `timingsMs` are ignored by Ktor (`ignoreUnknownKeys`).

`corrections` is the typed list (`kind`: `grammar` | `word` | `natural`, optional `explanation`, max 3, sorted by that priority). `explanation` is a short Russian sentence for the Telegram card; old records and clients can omit it. `notes` is the same list as `wrong|||better` strings, kept so an older Ktor still works when `ai-server` is redeployed first. Either deploy order is safe.

Notes on `ok`:

```json
{
  "jobId": "...",
  "status": "ok",
  "result": {
    "notes": ["I was in Turkey|||I went to Turkey"],
    "corrections": [{ "wrong": "I was in Turkey", "better": "I went to Turkey", "kind": "grammar" }],
    "transcript": "I was in Turkey last summer"
  },
  "transcript": "...",
  "replyText": "...",
  "timingsMs": { "stt": 1, "context": 1, "reply": 2, "notes": 2, "memoryExtract": 2, "dialogue": 1, "tts": 3, "memorySave": 1, "finalize": 1, "llm": 2, "total": 8 }
}
```

On `error`: `{ "code": "timeout"|"pipeline_failed", "message": "..." }` (message truncated to 240 chars).

`timingsMs` is diagnostic and backward compatible: existing `stt`, `llm`, `tts`, and `total` remain. The additional keys describe parallel stages and can be absent on clarify turns. Stage values overlap; they must not be summed. `total` now ends after memory, metrics, streak and call updates, immediately before the successful pipeline result. The AI-service completion log includes the job id. Ktor logs clip submission, polling, audio download, Telegram download, queue time, and reply delivery. These logs contain identifiers and timings, not transcripts or personal facts. Compare the Telegram handler's elapsed time for end-to-end user-facing latency.

`notes` is capped at 8 seconds from correction request start (fixed in `pipeline.py`) in ordinary and onboarding voice turns; the voice reply succeeds with empty corrections when the deadline expires. `/internal/metrics` includes Moscow-day `corrections` outcome counters with counts, summed elapsed milliseconds, and second-attempt counts. The existing `/admin/metrics` page renders these counters; see the spoken-corrections integration note for outcome definitions.

For onboarding voice turns, correction generation starts alongside the next-question assessment after STT. When the question is ready, synthesis starts without waiting for corrections; the result waits for both the audio and the bounded correction task. The final onboarding review still waits for saved correction candidates before verifying examples and composing the report. An assessment failure cancels unfinished correction work, and an audio failure keeps the saved assessment and corrections for retry.

### `GET /v1/clips/{jobId}/audio` → 200 `audio/ogg` or `audio/mpeg`, or 404

Ktor uses `reply.ogg` / `greeting.ogg` for OGG and `reply.mp3` / `greeting.mp3` for MP3 when calling Telegram `sendVoice`. Unsupported media types fail instead of being sent with a misleading extension. Audio bytes and `Content-Type` must agree. OpenRouter TTS passes through MP3 when `TTS_RESPONSE_FORMAT=mp3`, avoiding ffmpeg; other configured formats still produce OGG. Direct Deepgram can produce OGG or pass MP3 through without ffmpeg. Ktor MP3 support must be deployed before enabling MP3 output on AI.

### Session ids

Kotlin: `SessionId` inline value. Telegram: `"tg-$chatId"` (`telegramSessionId`). Negative chat ids become `tg--100` style.

### Redis dialogue

- Env: `REDIS_URL` (compose/VPS `redis://redis:6379/0`). Unset → `MemoryDialogueStore`.
- `DIALOGUE_TTL_SECONDS` default **2592000**, но фактическое хранение сырых реплик и TTL ограничены семью днями в `dialogue.py`.
- `DIALOGUE_MAX_MESSAGES` default **40** (trimmed from the front).
- JSON value: `{ "messages": [ { "role", "content" } ] }` — **assistant content is spoken reply only**, not notes.

Stored history is what the next LLM call sees (plus system prompt). `POST /v1/sessions` creates/refreshes the voice session; it does not wipe Redis history if the key already exists.

### Граница SP-01

`/internal/onboarding/state`, `/internal/onboarding/actions`, `onboardingRunId` и `result.onboarding` удалены.
Текстовый tutor API не использует clip jobs. `/start` в Telegram вызывает TutorRuntime, не greeting audio.
Контракт нового чата: [SP-01](tutor-chat.md#sp-01-постоянный-текстовый-чат-с-сашей).

## Сохранённый voice runtime

Саша использует отдельный текстовый prompt. Этот документ объясняет работающий voice-код и его совместимость.

### Персона, режим, история

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

### Голос, время и провайдеры

`/speed` хранит независимый `speech-speed:{sessionId}`: 0.8, 0.9 или 1.0.
Direct Deepgram использует этот параметр; обычный OpenRouter TTS его игнорирует.
`TTS_PROVIDER`, `TTS_OUTPUT_FORMAT` и media type определяют MP3/OGG; Kotlin выбирает соответствующее расширение.
Причина поддержки двух форматов — совместимость клиентов и устранение лишнего перекодирования для direct MP3.
Порядок переключения и rollback — в [deploy](operations.md#выкладка-и-восстановление); API — в [clip contract](voice-and-reviews.md#clip--session-http-contract).

Речевое время и длительность записи — разные поля. STT usage/cost использует длительность записи.
VAD выполняется параллельно STT; после STT ожидание ограничено 0.5 секунды.
При пустом STT счётное время равно нулю; при сбое VAD используется запись с причиной fallback.
Метод фиксируется для московского дня calls, чтобы цель не меняла знаменатель посреди дня.
Старые агрегаты не пересчитываются из отсутствующего аудио; источники и версии учитываются в аналитике.
Проверять `speech_time.py`, `pipeline.py`, `calls.py` и [технический контракт](analytics.md#дашборд-практических-звонков-telegram).

### Доказательства и ограничения

Исторические transport-пробы подтверждали PCM→OGG и speed routing, но не качество произнесённого текста.
History regression проверяла migration, retention, deduplication и retry identity; не живую смену персон.
VAD проверялся на десяти OGG с добавленными паузами и mock STT: при конкуренции часть clips использовала fallback.
Эти результаты не измеряют Telegram latency или точность по ручной разметке.

## Telegram practice calls

Situation practice extends the same call lifecycle. Its menu, scenario state, fictional
memory boundary, and MVP scope are recorded in
[технический контракт](voice-and-reviews.md#сохранённый-voice-runtime).

Это сохранённая функция голосовой практики, не trial нового репетитора и не Mini App.
Профиль `learner:` и rolling voice history сохраняются между calls; с `tutor:v1` они не связаны.

### When a call opens

Явный `Start call` или выбор ситуации вызывает `POST /internal/calls/start`.
Ktor проверяет активный Vocabulary Builder и существующий call; старого onboarding/score/goal gate нет.
Обычное голосовое сообщение **не открывает** call: без активного call оно игнорируется.
Внутренний `/internal/calls/open` остаётся API, но не делает голос входом в onboarding.
`/start` и `/onboarding` переходят к Саше, сохраняют память и не завершают call.
Напоминание само по себе не открывает call и не запускает clock.

Старт сохраняет вопрос и delivery flag в `call:{id}`; подтверждение доставки идёт через
`POST /internal/calls/starter-delivered`. Повторное нажатие при активном call не создаёт второй звонок.
Стартовый вопрос не добавляет goal seconds. `End call` возвращает прежнюю call-клавиатуру и запускает review.

### During the call

On an eligible `🎙 Start call` tap, Ktor first checks whether a call is already active, then shows `Speaky is joining the chat… 💙` with `ReplyKeyboardRemove` while ai-service prepares the starter voice. A generation failure restores the button with a retry message. At End, the review-wait message carries the persistent `🎙 Start call` reply keyboard and remains in the chat. The bot does not delete messages that set or remove the lower keyboard.

When a Telegram response will contain voice, Ktor shows the recording-voice action during voice preparation and again immediately before upload. This includes the greeting and the `Start call` opening, as well as Vocabulary Builder turns.

The starter voice now opens with `Hi, <Telegram first name>! How are you?` when the name is usable, or `Hi! How are you?` otherwise, followed by a short question. The name is supplied by Ktor in the internal start request; the LLM does not invent it. The model prefers an unfinished topic from an actual prior user turn and varies natural phrasing such as `Last time you told me about ...` or `We talked about ...`. It may claim a previous discussion only when that turn supports it; otherwise it uses durable personal context without that claim, or an everyday topic. In the retained call voice path, the `Your turn—send a voice message.` text carrying `ReplyKeyboardRemove` remains in the chat so the keyboard removal is retained. The uploaded voice filename follows `audioContentType` (`.ogg` or `.mp3`). Telegram draws the waveform, so its shape and the effect of deleting the removal message require a live DEV check.

The latest Speaky voice carries one inline keyboard: today's recognized speech against the daily goal (`8:24 / 10:00`), `Subtitles`, and `End call` on the next row, styled red where the Telegram client supports button styles. Without a goal, it shows elapsed time only (`8:24 today`). The keyboard moves off the previous voice. Crossing a chosen goal does not end the call. The clock shows overtime, such as `12:40 / 10:00`, and one text says the day's minutes are done; no-goal calls do not send this nudge.

A recognized turn stores transcript, reply, corrections, Whisper seconds, and word timings on `call:{id}`. Silence and the clarify line add no seconds. One live correction uses the shared correction card.

For a call opened with `🎙 Start call`, Speaky puts 👍 on the user's first processed voice reply. It saves the first and latest accepted Telegram voice message IDs in `call:{id}`, so a Ktor restart does not lose the target. When `End call` seals a call, Speaky puts ❤ on that call's latest accepted user voice. If the call has only one user voice, ❤ replaces its earlier 👍. Reaction failures are logged and do not block the voice reply or review.

The same Moscow day keeps the call open across pauses. `End call` waits until the voice already in the chat queue finishes, then seals. After End call, a voice alone does not open another call; the user must explicitly start it. Minutes for the day are the sum. `End call` is handled on `TelegramChatActions`, so it does not need a second lock.

At the next Moscow day, `open` seals the previous call. It does not push the review at night. The internal open operation can create a new call and offer yesterday's review; Telegram still requires an explicit start when no call is active. That button is offered once.

### Review

`POST /internal/calls/end` returns the sealed id. Telegram shows a typing indicator while `POST /internal/calls/review` checks the call. An insufficient result sends one formatted `🎙 Let’s talk a bit more` message with the persistent Start call keyboard. A full review sends `Thanks for the chat 💙` with that keyboard, followed by the first inline result card. A failed review sends a retry card after the thank-you message; Retry edits that card after success. A failed review is not cached; Retry calls the same route.

The model returns diagnostic text and a move from -2 to 2 for each existing score.
A null skill score receives its first independent placement from sufficient current-call evidence.
Existing score anchors remain for compatibility; the scale ends at A1/C1 (10–96).
Grammar examples are separately verified, with up to five retained examples and optional saved explanations.
Vocabulary upgrades are separate learning suggestions, not evidence of mistakes or a lower score.
[Correction contract](voice-and-reviews.md#коррекции-речи-в-сохранённых-calls) and
[Vocabulary workflow](voice-and-reviews.md#vocabulary-assessment-workflow) describe the validation.
Review model and fallback retain the ONBOARDING_REVIEW_MODEL names for compatibility.

The stepped snapshot is written back to `assessment:{sessionId}` and, when learner memory already exists, onto its proficiency scores and derived skill bands/positions. A later save of the same onboarding attempt does not overwrite that snapshot. SP-01 no longer creates completed voice-onboarding attempts. Ordinary turns still do not change proficiency.

Deploy ai-service before Ktor. Live Telegram acceptance is separate from the unit tests.

### Short calls and result order, 2026-10-24

For new ordinary calls, fewer than 30 recognized learner-speech seconds produces
one insufficient-speech message. It has no skill cards, score changes, inline
button, or character-feedback survey. At 30 seconds or more, the existing
review model marks only clearly insufficient connected speech as insufficient;
uncertain evidence gets a cautious full review. Insufficient calls still count
their recognized seconds toward daily activity and speaking time.

New full reviews use Grammar → Vocabulary → Fluency → Level. The Level card
shows the level, score, next-band progress, streak, and reminder offer when
needed. It omits the diagnostic paragraph, accuracy note, and daily time.
The character-feedback survey follows that card. The model no longer writes a
conversation recap for new reviews. Cached reviews without a presentation
version retain the old level-first carousel, followed by Grammar, Vocabulary, Fluency and a recap/progress card.
The review response distinguishes `reviewKind=full|insufficient` and
`presentationVersion=2`; Ktor defaults missing fields to the old format.
The agreed behavior and verification boundary are in
[технический контракт](voice-and-reviews.md#сохранённый-voice-runtime).

### DEV call-review timeout, 2026-10-23

Two DEV calls showed `Couldn't score this conversation. Try again.` because
Ktor timed out after about 10 seconds on `POST /internal/calls/review`. AI
continued and saved both reviews; the model did not fail. The review request
now has a 90-second request and socket timeout, like call start. Other AI
requests keep their existing limits. A delayed-response HTTP test covers the
override. The existing Retry button can read the saved review without scoring
the call again. A live Telegram retry remains to be checked after deployment.

## Память сохранённых звонков

> Область — память прежних звонков `learner:`, не новый чат `tutor:v1`. Историческое seeding из завершённых attempts сохранено; новый voice onboarding удалён. Общий профиль явно отложен.

Scope: personal facts, fixed proficiency, and context for returning after an absence. No weakness curriculum, daily timer or automatic rescoring is introduced.

### Context shared across paths

`Personalization` prepares context for ordinary clip replies and retained continuation helpers. Telegram no longer exposes the old onboarding continuation flow. `main.py` connects `Personalization(LearnerStore, LearnerModel, ...)` to the clip pipeline. It does not connect TutorStore. The continuation additionally receives the last eight dialogue messages so it can follow up on a concrete detail instead of restarting the interview.

The shared instructions prioritize the latest user statement and explicit requests such as “speak more simply”; remembered content is data, not instructions. Every spoken turn starts with a reaction to that statement. Speaky is an English teacher who loves teaching people to speak; when they ask about her, she answers from that, without inventing a shared job or hobby. If they asked something, the answer comes before her own question. A phrase that is not a question still cannot be skipped. Getting to know the person continues from the reaction and does not replace it. A remembered interest is used only when the reaction reaches it. Personal callbacks are optional and relevant, never compulsory every turn. Proficiency guides vocabulary, phrase length and complexity with an occasional small comprehensible stretch. The assistant does not disclose the scores or turn ordinary conversation into a lesson.

### Persistence and updates

`learner:{sessionId}` has no TTL and stores person information, proficiency, the assessment run ID and `lastConversationAt` (UTC epoch seconds). It is independent of the rolling 40-message dialogue and the current onboarding attempt; `/onboarding` does not erase it. Memory store supports the same behavior in tests.

Person contains name, work, leisure, English goal and at most 12 short facts. Only explicitly stated information is extracted. Contradictions replace old facts; requests to forget remove them. Fields are schema/size validated; this is model extraction, not a proof that every fact is correct. Data never becomes system instructions. Sensitive traits are not inferred. Extraction consumes existing memory plus user transcripts, not assistant-generated claims.

A historical completed onboarding can seed the person from its transcripts and proficiency from the stored overall CEFR/position and per-skill band/position/score/confidence. Repeating the same completion does not reseed. Call reviews update proficiency; new voice-onboarding attempts are no longer created. An ordinary voice never changes it. A sealed practice call may move each stored score by at most two points; repeated calls accumulate those changes and can cross position or CEFR boundaries. That stepped snapshot is what the next conversation reads. Completed legacy attempts are lazily imported, including their transcript details. Old completed attempts are imported before explicit reset. A legacy summary without its original attempt can supply proficiency but cannot recover personal facts.

The ordinary voice reply reads the current transcript and recent dialogue immediately. After the clip pipeline succeeds, AI-service updates `lastConversationAt` and puts the transcript into a Redis-backed memory queue before marking the clip complete. Enqueue and contact writes have short deadlines and fail without suppressing the voice reply; an `enqueue_failed` metric means that particular fact update cannot be recovered. A failed, cancelled, clarification, scenario, or load-test turn does not enqueue a fact update. The next ordinary turn still reads the previous transcript from rolling dialogue history even if the background update is pending.

The worker runs up to four sessions concurrently and processes each session's jobs in FIFO order. It reads current learner facts, requests a strict JSON schema response, validates field types and limits locally, and saves only a validated result. Extraction has a 20-second budget across at most two model calls; a failed first call gets one retry within that budget. A save failure temporarily keeps the validated candidate in the job and retries saving without another model call. Queued transcripts and any candidate expire after seven days. Redis leases let another worker reclaim a job after a restart, and a job ID stored with the learner record prevents applying a saved update twice. Profile writes compare the current Redis value before committing so assessment changes are not overwritten. Final failure leaves prior facts intact and records a fixed reason code without the transcript or personal values.

Background extraction uses `PERSON_MEMORY_MODEL`, defaulting to `openai/gpt-4o-mini`, independent of `LLM_MODEL`. Onboarding seeding and ordinary dialogue retain their existing model selection. The former hardcoded provider route motivated a configurable extraction model; see the dated evidence below.

The learner record still has no TTL and is read across later conversations for the same session ID. A next turn sent immediately may see the prior fact set through durable learner memory while still seeing the current conversation through dialogue history. Telegram chat IDs map to stable session IDs across calls and days; different mobile session IDs do not share this memory automatically. Redis/context failures fall back to general conversation instructions.

`/internal/metrics/prometheus` exports `speaking_memory_jobs_total{outcome,reason}`, `speaking_memory_jobs_pending`, and `speaking_memory_oldest_pending_seconds`. Reasons are fixed codes such as `facts_missing`, `facts_too_long`, `provider_timeout`, and `save_failed`; a successful retry counts as one final success, not a failed memory update. These process counters require Prometheus scraping for history across AI-service restarts. A gauge value of `-1` means the queue could not be read. No transcript, fact value, chat ID, or username appears in metric labels.

### Returning and streak

Context is read before recording the new turn. It includes the current displayed streak, previous contact timestamp and calendar-day distance in Europe/Moscow. At two or more calendar days, the first response may briefly welcome the user back. Exact day counts are optional; no guilt, invented events or claims of missing the user. This is calendar-day distance, not the number of missed practice days.

Successful contact consumes the return cue for later replies. `Keep talking` suppresses absence greetings and marks contact without incrementing streak or generating new personal facts from the button click. No reliable timestamp exists for legacy history, so the first migrated response does not invent an absence. Silence/clarification does not update personal memory or its contact timestamp. Existing streak calculation is unchanged.

### Validation

Tests cover person replacement, fixed proficiency, memory/Redis persistence, no TTL, repeat greeting suppression, continuation context and recent history, legacy import, reset preservation, invalid model JSON and provider failure fallback. Background queue tests cover FIFO, duplicate enqueue, restart after a profile save, retry after invalid schema and save failure, parallel sessions, and immediate next-turn history. With the target provider credentials, run `PYTHONPATH=. uv run --python 3.12 --with-requirements requirements-dev.txt python evals/check_person_memory.py` from `ai-service/` for synthetic move/contradiction/forget cases. Real-model structured output and fact quality still require that live check; local tests use controlled models. No new deployment or live Telegram test was performed during this documentation review.

### Причина отдельного PERSON_MEMORY_MODEL

Аудит 2026-10-20 зафиксировал 35 HTTP 403 от Gemini review route в логах за 14–20 октября.
Изолированные запросы тем же PROD-ключом не прошли ни Google AI Studio, ни Google Vertex.
Fallback gpt-4o-mini завершал reviews; отдельная синтетическая extraction-проба на нём прошла.
Поэтому review, correction verification и personal extraction получили независимые настройки моделей.
Это не доказательство, что именно extraction вызывала наблюдавшиеся review failures.
Env override имеет приоритет над кодовым default; смена default сама по себе не исправляет окружение.
Аудит не менял PROD env и не выполнял deploy. Доступность провайдеров сейчас из этих результатов не следует.

## Коррекции речи в сохранённых calls

Текстовый репетитор использует отдельный prompt. Этот контракт относится к clip pipeline и post-call review.
Источник: `ai-service/app/correction_policy.py`, `llm.py`, `pipeline.py` и `review_content.py`.

### Live correction

Модель ищет явные синтаксические и лексические ошибки во всей распознанной реплике.
Новые кандидаты имеют `kind=grammar`, `vocabularyCategory=null`. Нормальная простая речь не является ошибкой.
Повторы, самокоррекция, незаконченная мысль, пунктуация STT и сомнительное распознавание не должны давать карточку.
Приоритет — не исправить правильную речь; полнота поиска не гарантируется.

Модель возвращает скопированный `context`, локальные `error` и `replacement`, причину и decision flags.
Код требует high confidence, definitely_wrong/worth_showing/understandable_alone=true,
is_spoken_language_artifact/is_asr_uncertain=false. Контекст содержит не более 30 слов;
исправление должно однозначно совпадать по целым словам. Пересечения, дубли и неподтверждённые spans отбрасываются.
Проверка учитывает апострофы и защищает от удаления модального/основного глагола.
Flags — суждение модели, а не независимая проверка аудио или смысла.

Публичный формат: `corrections: [{wrong, better, kind, explanation?}]`.
Legacy `notes: ["wrong|||better"]` сохраняется для клиентов.
Русское объяснение необязательно: одна строка/предложение, до 160 символов, без общего шаблона.
Telegram показывает не более одной карточки за ход; независимые принятые ошибки сохраняются для review.
В карточке выделяется локальная правка внутри контекста. Английские фрагменты объяснения выделяются monospace.

### Ограничения и сбои

Notes генерируются параллельно ответу; бюджет — 1200 tokens, общий deadline — 10 секунд.
Для notes задан reasoning.effort=none. Внутри deadline допустимы максимум две provider attempts;
валидный пустой ответ и safety-filtered candidate не вызывают повтор.
SDK retries отключены; явный controller отвечает за retry и backoff.
Сбой notes не блокирует готовый голосовой ответ. Deadline или неполный JSON могут потерять все кандидаты хода.

`metrics:corrections:YYYY-MM-DD` считает один конечный outcome на запрос по московскому дню,
включая elapsedMs и secondAttempts. `shown` означает прохождение AI-фильтра, не доставку Telegram-карточки.
Тексты реплик не входят в эти метрики. `/internal/metrics` и `/admin/metrics` показывают агрегаты.

### Финальный review

Сохранённые ошибки проходят отдельную проверку ID группами до пяти, до трёх групп параллельно,
с общим deadline 10 секунд. Успешные группы сохраняются при сбое другой группы.
Выбираются до пяти Grammar examples; объяснения берутся из сохранённого хода.
Legacy `word`/`natural` должны пройти ту же проверку; старые alternatives не становятся ошибками.
Готовые cached reports не регенерируются.

Vocabulary upgrades ищутся отдельно по всему диалогу и не снижают score:
[актуальный workflow](voice-and-reviews.md#vocabulary-assessment-workflow).
Почему сохранены отдельные поля review: [voice runtime](voice-and-reviews.md#сохранённый-voice-runtime).

### Доказательства

Unit tests проверяют parser и gates, но не качество решений модели.
Причины ограничений и результаты прежних проб приведены ниже.
`evals/spoken_corrections.json` содержит синтетические cases; они не измеряют ошибки распознавания реального аудио.
Новых платных прогонов при обновлении документации не было.

### Почему нужны deadline и проверка смысла

В прежнем DEV-ходе notes заняли около 18 с; отдельный PROD audit нашёл три review без примеров из-за timeout verifier.
Поэтому notes и финальная проверка имеют отдельные deadlines и диагностику исходов.
Синтетический прогон с нулевыми ошибками по меткам всё же содержал опасное `can to swim` → `swim`:
сохранён gate против удаления модального глагола. Verifier также мог принять пару отдельно и отвергнуть в batch.
Grounding, confidence и JSON не доказывают смысловую правильность; финальный отбор не восстанавливает пропуски discovery.
Полные прежние результаты доступны в Git на `60d4085`; это не приёмка текущей модели.

### Coaching quote (`CoachingFeedback.kt`)

Input is `List<Correction>` (`speaking/ClipProcessor.kt`): `wrong`, `better`, `kind` (`GRAMMAR` / `WORD` / `NATURAL`, or `null`), and optional `explanation`. `HttpClipClient` builds it from `result.corrections`; if that is empty it falls back to legacy `result.notes` (`wrong|||better`, `parseCorrections`) with `kind = null` and no explanation. Older saved onboarding corrections also have no explanation.

Priority `grammar` > `word` > `natural` > no kind. The live card shows at most one correction in retained practice calls. The other accepted corrections from that turn remain available to a practice-call final assessment.

For the matched correction Telegram shows `Ранее ты сказал:`, then the short context with only the changed words in **strikethrough** and their replacement in **bold** beside them. An optional `💡 <explanation>` follows after one blank line in plain text. Retained voice calls use this card. Other words from the transcript are not repeated alongside a correction; the context is already self-contained. Unmatched `wrong` is skipped. A match counts only as a whole word or phrase (letters/`'` on either side block it), so `me` does not cut `remember`. Overlapping spans: higher-priority kind wins; same kind → earlier start, then longer. With no matched corrections, ordinary dialogue retains the existing `🗣️ You said:` blockquote.

If transcript is blank, **no** text quote is sent (voice still sent).

### LLM (`ai-service/app/llm.py`)

Two completions in `ClipPipeline` (`asyncio.gather`):

- **Reply** (`REPLY_SYSTEM`): JSON `{"reply"}` only. History included; `temperature` 0.7. Spoken turn is about three to six sentences: a specific reaction to what they just said, then one question that continues it. This is the voice pipeline, not Sasha’s text prompt. No corrections in the reply.
- **Notes** (`NOTES_SYSTEM`): full transcript, `temperature` 0, 1200 output tokens. The model checks errors and optional Vocabulary alternatives in the same request. It returns exact local phrases, confidence, a short Russian reason, and ASR/artifact and usefulness decisions. Code grounds each edit in the transcript and removes duplicates/overlaps. There is no per-turn numeric candidate cap; the output-token limit and 10-second deadline still bound what the model can return. Empty output is valid for uncertainty or lack of a useful teaching point. Legacy bare strings and missing/unknown categories are rejected; outgoing legacy `wrong|||better` remains supported for clients.

Grammar is a genuine grammatical error; word is an incorrect word/collocation for the intended meaning; natural is clearly unidiomatic phrasing. Alternative is a useful optional expression for an already correct phrase. Spoken fillers, self-repair, ASR punctuation and regional variants must not trigger corrections. The one live card prioritizes errors. Error cards strike through only erroneous words; alternative cards do not strike through the correct original. See [shared spoken-correction policy and evaluation](voice-and-reviews.md#коррекции-речи-в-сохранённых-calls).

The spoken reply retains its existing token budget. Both calls use `response_format=json_object`. `LLM_MODEL` and `NOTES_MODEL` both default to `openai/gpt-5.6-luna` via OpenRouter (`OPENAI_BASE_URL` default `https://openrouter.ai/api/v1`); credential name remains `OPENAI_API_KEY`.



## Vocabulary assessment workflow

> Workflow сохранённых voice reviews. Применение к новым onboarding attempts прекращено в SP-01; исторические assessment остаются. Это не пять показателей прогресса v0.5.

Контракт сохранённого call review, сверенный с локальными исходниками. Живое качество модели не подтверждается наличием кода.

Review blocker: an earlier real-dialogue probe accepted `step` → `stop` while
calling `step` erroneous. The revised code requires source correctness,
meaning preservation, and learning value separately, but model judgment still
needs real-dialogue and held-out evaluation for acceptance.

### During a learner turn

The live notes request checks the entire recognized turn for clear syntax and
lexical errors. It returns `kind=grammar` and `vocabularyCategory=null`.
A natural but basic phrase does not produce a live correction. The parser
requires a copied context, one exact local edit, high confidence, and the
spoken-language/ASR safeguards. The bot displays at most one correction card
per turn. Other independent corrections are saved.

### At the end of practice calls

Calls collect saved errors, verify them in bounded batches, and select up
to five Grammar examples. The examples reuse Russian explanations saved during
the turn. Optional live alternatives from the previous local workflow are
ignored. Older saved `word` and `natural` corrections become Grammar candidates
and must pass the same strict verifier. A failed verification batch does not
remove successful batches. The review model sees verified examples when
writing diagnostic text and scores. Calls limit each existing score move to two points. If a legacy assessment has a null
skill score, a call may place that skill for the first time from its own speech.

In parallel with correction verification and review composition, a separate
model request searches every numbered learner turn for optional Vocabulary
upgrades. It receives the learner transcript and brief assistant context.
Long dialogues are split into bounded chunks, with up to three concurrent
searches. Search results must quote an exact source in the given turn and
change only one word or a short local expression. A second model call verifies
the proposal in its full transcript. The final list has up to five
`word_upgrade` and five `expression_upgrade` suggestions. The output shape is
`{original, alternative, explanation, category, context, editedContext}`.
Model order is refined by the proposed benefit rank across chunks. An upgrade
is not an error and is not
sent to the score model as evidence of a mistake.

The model's category label is advisory. Grounding removes unchanged outer
words when the remaining source occurs once in context. It then classifies a
one-word-to-one-word edit as `word_upgrade`; any other short local edit is
`expression_upgrade`, including two words to one word. It accepts at most four
source words and five replacement words and does not reject an exact copied
context solely because the learner's turn exceeds 35 words. The separate
verifier decides separately whether the original is correct, the replacement
preserves meaning, and it offers learning value. Its accepted reason explains
when to use the replacement. Both categories retain their five-item limits.
The private debug trace keeps the proposed label and span when normalization
changes them.

The Telegram Vocabulary result is one card with score, diagnostic text, and
ranked optional lessons. Each lesson shows the learner's complete short phrase,
the edited phrase, and a usage explanation. The best lessons that fit the
message-length budget are shown, up to ten. If none survive, the card still
shows the score and diagnostic text, then navigates to Fluency.

### Failure and observability

The separate search has an 18-second deadline; upgrade verification has a
12-second deadline. One failed chunk or batch does not remove successful
results. A complete failure yields no upgrades; it does not remove diagnostic
examples or the score. Correction verification retains its own 10-second
batch deadline. `/metrics` exposes Vocabulary enrichment run outcomes and
duration, plus proposed, grounded, verified, and selected candidate counts by
flow. These metrics have no user, transcript, or category labels.

For future debugging, the upgrade verifier returns one accept/reject decision
and a short Russian reason for every candidate ID. The service keeps a private
trace of each proposal's source turn, local replacement, status, and reason.
The trace also names the failed grounding rule, overlap with an error,
verification failure or timeout, and proposals accepted by the verifier but
omitted by the final limit or deduplication. It is stored in Redis under
`vocabulary-review-debug:{onboarding|call}:{runId|callId}` and expires seven
days after the oldest included learner turn was received. The in-memory test
store enforces the same read deadline. No trace text is returned to the learner
or written to metrics and service logs. Existing probe results cannot be
backfilled with model reasons; only new reviews create these traces.

### Validation boundary

Local parser, review-flow, failure-isolation, and Telegram rendering tests
pass. The prompts and model gate still need a paired quality evaluation on
real dialogues and a held-out sample for acceptance. In particular,
model-assigned benefit rank and learning value are judgments, not deterministic
facts, and transcripts alone cannot resolve uncertain audio.

### Основания разделения ошибок и upgrades

Прежние mixed prompts повторяли известную ошибку как alternative, а расширенный discovery увеличивал число ложных карточек.
Поэтому upgrades ищутся отдельно и исключают пересечения с исправлениями.
Verifier принимал attendance → participation и `step` → `stop`, называя исходник ошибочным:
correctness исходника, сохранение смысла и учебная ценность требуют отдельных решений.
Набор proposals менялся даже при temperature=0; сравнение разных запусков не изолирует эффект parser.
Review prose могла делать неподтверждённый вывод при пустом списке examples.
Это основания проверять полный контекст и спорное аудио на отдельной held-out выборке, не оценка production precision.

## Telegram progress profile

`/profile` is the overview of the user's English progress. It is registered in the bot command menu and accepts `/profile@bot`. It sends a text card with the Telegram first name, latest completed assessment (CEFR, overall score, points to next band, Grammar/Vocabulary/Fluency scores), saved daily minutes and current streak. It adds no onboarding screen. The card explicitly labels the assessment as latest; practice-call reviews may update the stored assessment under their own scoring rules.

The profile card has no inline `🔥 View streak` button. `/streak` remains the detailed calendar view. The old `profile:streak` callback still works on cards already sent before this change. History, best streak, total speaking time and a progress-history button are outside this MVP.

### Data and persistence

Authenticated `GET /internal/profile/{session_id}` returns:

```json
{
  "assessment": {
    "cefr": "B1",
    "overallScore": 52,
    "nextBand": "B2",
    "pointsToNext": 9,
    "grammar": 52,
    "vocabulary": 52,
    "fluency": 52
  },
  "dailyMinutes": 10,
  "currentStreak": 4
}
```

AI-service composes this response without creating an onboarding attempt, calling a model or recording activity. It reads:

- `assessment:{sessionId}`: latest completed score summary, no TTL. Legacy completed attempts populated this summary; retained practice-call reviews can update it. In-memory storage has the same behavior. Starting another attempt preserves the snapshot until a new assessment completes.
- `practice-goal:{sessionId}`: existing daily-minutes choice, outside the attempt.
- Existing streak storage: current display value including expiry, using the existing Moscow-day rule. A completed voice exchange counts; fulfilling the full daily goal is not required.

Legacy completed attempts without a snapshot remain readable. `/onboarding` no longer resets legacy attempts; it continues the text tutor. Already-reset attempts from before this release cannot be recovered. This score snapshot is separate from the durable conversational learner memory described in [personalized conversation](voice-and-reviews.md#память-сохранённых-звонков); both now survive a new attempt.

No assessment gives `assessment: null`; goal and streak are still shown. The current rendering still says “Complete /onboarding to get your first assessment.” This is stale runtime copy: `/onboarding` now opens the text tutor and cannot produce that assessment. It is a documented open inconsistency, not a supported assessment path. Missing CEFR or individual scores remain unknown, never invented zeros. No goal, including an explicit `No goal for now` choice stored as zero minutes, shows `Not set yet`. C2 has no numeric overall score or next-band target. No active streak is `0 days`.

### Совместимость шкалы старых оценок

В `review_score.py` прежние оценки используют band, position и shade, а не прямой выбор числа моделью.
Центры low/mid/high: A1 12/20/27, A2 32/38/43, B1 47/52/57, B2 63/70/77, C1 83/90/94.
Shade `--`, `-`, `0`, `+`, `++` сдвигает центр максимум на два пункта с ограничением внутри ячейки.
Начала диапазонов: A1 10, A2 30, B1 46, B2 61, C1 81; `pointsToNext` — расстояние до начала следующего.
Общий CEFR не усредняет три skill score. При недостатке данных оценка отсутствует; C2 не имеет числового балла.
Это совместимость исторических assessment и calls, не методика пяти показателей AI-репетитора.

### Граница нового продукта

Это прежний профиль голосовой практики. Он не читает TutorStore и не показывает roadmap из v0.5.
Старый handoff через daily goal/reminder после voice onboarding удалён, как и call gate по оценке.
`/onboarding` теперь открывает текстовый чат; он не пересчитывает CEFR.

### Verification and release

Tests cover summary persistence in memory and Redis, reopening storage, legacy completed attempts, reset and replacement by a new completed assessment, goal/streak preservation, authenticated endpoint and client, rendering unknown values, actual streak in the final message, and calendar callback data. Deploy AI-service before Ktor through the existing deployment workflows. Live Telegram acceptance remains a separate check; no messages or deployments are part of local tests.

## Feedback завершённого call

`POST /internal/calls/feedback` требует internal token. Actions: offer, rate, answer, skip;
choice: liked|neutral|disliked. Повторные rating/answer не заменяют принятые значения.
Comment ограничен 500 символами; обычный текст может ответить на survey в течение 24 часов.
Команды и ввод времени reminders сохраняют собственную обработку.
`GET /internal/calls/feedback?offset=0&limit=25` возвращает rated responses и total.
Dashboard `/admin/metrics/calls` экранирует username/comment и использует Cache-Control: no-store.
Insufficient review не предлагает survey; full review предлагает его после последней карточки.

### Mode-based feedback

New offers use `mode-feedback:entry:{instanceId}` and one accepted-rating marker
per Telegram session and mode: onboarding, free conversation, or all situations.
The call ID identifies a completed free/situation conversation; the onboarding
run ID identifies a completed reviewed attempt. Every eligible completion can
show the existing rating buttons until a rating is accepted in that mode. A
skipped optional comment or unanswered rating does not stop later offers.
The prompt is confirmed after Telegram sends it; a failed send releases its
claim. The first accepted rating wins under a Redis transaction. The optional
text answer still has a 24-hour window. Feedback responses and dashboard rows
carry mode and character independently.

Old `first-call-feedback` and `kora-call-feedback` rows remain readable with
their original character labels. At first new offer, a past rating suppresses
the matching mode only if a linked call still proves whether it was free or a
situation. Expired or missing scenario data is not guessed. New call ratings
are also written to the old key where possible for a rollback; the dashboard
deduplicates such rows by call ID. Onboarding ratings have no old key.


### Вход в согласованный урок

Mini App автоматически проверяет `lesson_status` (Telegram initData, владение уроком, серверное время).
До начала показывает ожидание и перечитывает запись каждые 15 секунд или к началу: перенос и отмена учитываются без запроса микрофона.
В назначенное время запрашивает микрофон и подключается без кнопки старта; backend повторно проверяет допуск при открытии WebSocket.
После готовности realtime-соединения Саша приветствует голосом; переподключение продолжает тот же урок.
При запрете/отсутствии/занятости микрофона — понятная ошибка и повтор подключения. Системное разрешение выдаёт пользователь.
Если браузер блокирует AudioContext, остаётся исключительная кнопка «Включить звук»; обойти ограничение автозвука нельзя.
Закрытие Mini App останавливает ожидание и микрофон, включая поздний результат запроса разрешения.
