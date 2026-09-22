# Architecture

Глобальная карта **текущего** кода (ветка с Telegram-ботом Speaky). Не путать с `product.md`: там целевой продукт (диагностика speaking + персонализация). Сейчас в проде — голосовой диалог в Telegram, точечные grammar/lexis notes в цитате, история в Redis.

Детали контрактов: `integrations/2026-09-18-clip-session-api.md`, Telegram: `integrations/2026-09-18-telegram.md`. Карта файлов: `architecture/2026-09-18-system.md`. Деплой: `deploy.md`.

## Что есть в системе

| Слой | Где код | Роль |
| --- | --- | --- |
| Telegram | `cmp/server/.../telegram/` | Webhook, `/start`, голос, цитата, очередь |
| Ktor | `cmp/server` | TLS webhook, health; клипы только внутренним `HttpClipClient` |
| ai-service | `ai-service/` | STT → LLM → TTS, сессии, jobs |
| Redis | контейнер `redis` | `session:{id}`, диалог до 40 сообщений, TTL 30 дней |
| CMP UI | `cmp/app/` | Releva Auth/Home/Call(WebRTC)/Review против Ktor; карточка «Последний разговор» мок. **Не** clip API |

Внешние API: Groq Whisper (STT), OpenRouter `gpt-4o-mini` (LLM), Kokoro TTS, Telegram Bot API.

## Прод: компоненты

```mermaid
flowchart LR
  user[User]
  tg[Telegram]
  ktor[Ktor_speakingCoach]
  ai[FastAPI_aiService]
  redis[(Redis)]
  groq[Groq_STT]
  orouter[OpenRouter_LLM]
  kokoro[Kokoro_TTS]

  user -->|voice_and_start| tg
  tg -->|HTTPS_webhook| ktor
  ktor -->|POST_sessions_clips_poll| ai
  ktor -->|sendVoice_and_quote| tg
  ai --> redis
  ai --> groq
  ai --> orouter
  ai --> kokoro
```

Ktor на VPS слушает **443** с PEM, регистрирует webhook с сертификатом. Секрет заголовка `X-Telegram-Bot-Api-Secret-Token`. Ответ Telegram **200** сразу, обработка в отдельном scope.

## Голосовой ход

Бот Speaky больше не ходит в clip jobs. Голос — `POST /api/v1/walkie-talkie` на `AI_SERVICE_BASE_URL` (`session_id` = `tg-$chatId`). Цитата — транскрипт без notes. Приветственный голос `/start` — `TODO` в чат. Диаграмма ниже — прежний in-repo ai-service (требования к клипам); CMP realtime/review по-прежнему зовут этот же `AI_SERVICE_BASE_URL`.

## Голосовой ход (прежний clip pipeline)

```mermaid
sequenceDiagram
  participant User
  participant Telegram
  participant Ktor
  participant Queue as SessionClipQueue
  participant Ai as ai_service
  participant Redis

  User->>Telegram: voice
  Telegram->>Ktor: POST /telegram/webhook
  Ktor->>Ktor: ensureSession tg-chatId
  Ktor->>Queue: submit clip
  alt already processing
    Ktor->>Telegram: Got it I will answer in order
  else queue full max 3
    Ktor->>Telegram: Too many voice messages
  end
  Queue->>Ai: POST /v1/clips 202 jobId
  loop poll 300ms until 90s
    Queue->>Ai: GET /v1/clips/jobId
  end
  Ai->>Ai: STT
  alt empty or no_speech
    Ai->>Ai: TTS clarify no LLM
  else speech
    Ai->>Redis: history
    Ai->>Ai: parallel LLM reply plus notes
    Ai->>Redis: append user and spoken reply
    Ai->>Ai: TTS reply
  end
  Queue->>Ai: GET audio
  Ktor->>Telegram: You said quote if transcript
  Ktor->>Telegram: sendVoice OGG
```

Идентификатор сессии Telegram: `tg-$chatId` (`TelegramHandlers.telegramSessionId`). В Redis ключ `session:{sessionId}`. `/start` — get-or-create, историю не стирает.

## Пайплайн ai-service

```mermaid
flowchart TD
  clip[POST_v1_clips]
  job[JobStore_pending]
  stt[Groq_Whisper]
  clarify{empty_or_no_speech}
  reply[OpenRouter_reply_JSON]
  notes[OpenRouter_notes_JSON]
  tts[Kokoro_then_ffmpeg_OGG]
  redis[(Redis_dialogue)]
  ok[job_status_ok]

  clip --> job --> stt --> clarify
  clarify -->|yes| tts
  clarify -->|no| redis
  redis --> reply
  redis --> notes
  reply --> redis
  reply --> tts
  notes --> tts
  tts --> ok
```

- Clarify: *I didn't catch that. Could you say it again?* Notes пустые, LLM не зовётся.
- Два параллельных LLM-вызова (одна модель, один ключ): reply JSON `{"reply"}` с историей, `temperature` 0.7; notes JSON `{"notes":[{"wrong","better"}]}` **без** истории, `temperature` 0. Промпт задаёт ширину спана (few-shot), не каталог ошибок. Пайплайн ждёт оба, потом TTS. В Redis кладётся **spoken reply**, не notes.
- Notes в job: строки `wrong|||better` (макс. 3). Цитата в Telegram: strike на одной строке, bold `better` на следующей; `wrong` только как целое слово/фраза; висячая пунктуация после спана съедается.

Jobs в памяти процесса, TTL ~10 мин. Рестарт ai-service убивает незавершённые jobs, **не** Redis-диалог.

## Локально vs VPS

```mermaid
flowchart TB
  subgraph local [Local_unit]
    gradle[gradle_server_test]
    pytest[ai_service_pytest]
  end

  subgraph dev [DEV_Redeploy]
    ktorDev[cmp_server]
    aiDev[ai_server]
    redisDev[redis]
    ktorDev --> aiDev --> redisDev
  end

  subgraph vps [Prod_Redeploy]
    ktorProd[speaking_coach_443]
    aiProd[ai_service]
    redisProd[redis]
    ktorProd --> aiProd --> redisProd
  end
```

- Локально: `:server:test` / `pytest` / `:server:run` без Telegram.
- Интеграция Speaky: Redeploy на DEV-хосты.
- Прод: workflow Redeploy PROD, secrets `PROD_*`. Имя/аватар бота — BotFather, не репозиторий.

## Что сознательно не в рантайме бота

CMP Android / iOS / Desktop (`App.kt`) — мок-экраны Releva, к сессиям и клипам не подключены. Для бота Speaky их не трогать, пока нет отдельной задачи на клиент.

## Границы ответственности при правках

```text
Telegram UX / очередь / цитата     → cmp/server/.../telegram + speaking
HTTP клиент к FastAPI (внутренний токен) → cmp/server/.../ai
TLS, webhook vs HTTP-only          → AppConfig.kt, Application.kt
STT LLM TTS Redis notes prompt     → ai-service/app
VPS scripts / CI secret names      → infra/, .github/workflows
```
