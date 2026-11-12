# Текстовый репетитор и Telegram

Контракты базовой поставки SP-01 и сохранённого legacy-кода. Новый фундамент SP-00 использует PostgreSQL;
legacy сохраняется отдельно, без переноса его Redis-хранилищ и без обязательного подключения старых функций к новому runtime.
Готовность изоляции и переключения текущего чата проверяется по [статусу](../status.md).
Описание legacy API ниже не означает, что эти маршруты доступны в новом runtime.

- [SP-01: постоянный текстовый чат с Сашей](#sp-01-постоянный-текстовый-чат-с-сашей)
- [Telegram: текущая маршрутизация](#telegram-текущая-маршрутизация)
- [Совместимость сохранённых систем](#совместимость-сохранённых-систем)

## SP-01: постоянный текстовый чат с Сашей

Реализация развёрнута на DEV; живая диалоговая приёмка остаётся открытой. Контракт: [утверждённый план](../plans/2026-11-09-sp01-text-onboarding.md).
Полная [v0.5](../product/2026-11-08-ai-tutor-experience-v0.5.md) остаётся направлением будущего продукта.

### Реализованный путь

Ответы Саши и follow-up после урока преобразуют `**жирный**`, inline code и fenced code
в Telegram `entities`. Остальная разметка остаётся текстом; HTML/Markdown parse mode не включён.
Длинные ответы делятся после преобразования, с сохранением UTF-16 offsets и целых surrogate pairs.
Контракт Telegram: [MessageEntity](https://core.telegram.org/bots/api#messageentity).
Поставка исправления форматирования указана в status.md.

```text
Telegram /start, /onboarding, свободный текст
  → TutorClient → /internal/tutor/start|turn
  → TutorRuntime → native LLM function calls → проверка → TutorStore
  → LLM получает фактический результат → русский ответ
  → Telegram reply + карточка только при изменении встречи
```

С Redis один conversation ID переживает знакомство, запись, отказ, отмену, повторный `/start` и рестарт. In-memory store рестарт не переживает.
Никакой обязательной анкеты или терминального состояния чата нет. Приветствие генерирует модель.
Личность выделена из Kora в `kora_personality.py`; исходный free-call prompt собирается с тем же текстом личности.
Чат использует настроенную модель Kora H, а при отсутствии отдельного Kora model adapter — общий LLM с той же личностью.
Текст любого языка передаётся модели; отвечает Саша по-русски. Голос в обычном чате не запускает STT/TTS.
Явно открытые прежние calls и Vocabulary Builder сохраняют свои обработчики и голосовые возможности.
Их наличие не превращает trial-заглушку в реальный звонок.

### API и данные

Все маршруты защищены существующим `X-Internal-Token` middleware.

- `POST /internal/tutor/start`, `POST /internal/tutor/turn`: `sessionId`, `requestId`, `text`, `receivedAt` (Unix seconds).
  Telegram adapter передаёт исходное `message.date`, не время ответа LLM.
- Ответ: `conversationId`, `reply`, `booking`, `bookingText`, `degraded`, `replyMessageId`, `cardMessageId`.
  `booking` отсутствует на обычных ходах; карточка не повторяется автоматически.
- `POST /internal/tutor/delivery`: подтверждает отправленный reply/card, сохраняет Telegram message ID и версию встречи.
- `GET /internal/learners/{sessionId}/context`: Personal/Learning context нового текстового чата, сводка, trial preference и текущая встреча.
  Маршрут читает только TutorStore. Calls читают LearnerStore (`learner:`); синхронизации нет. Общий профиль отложен, lesson runtime не реализован.
- `GET /internal/tutor/analytics?since=...&until=...`: когорта по времени начала знакомства, конверсия,
  активные записи, переносы, отмены, повторные записи, предложения, ошибки и доставленные requests.
  Чтение learner context само по себе не начинает знакомство. Перенос не увеличивает конверсию.

Namespace: `tutor:v1:{stable sessionId}`. Используется существующий Redis connection; без Redis — локальный memory store.
Personal Memory, Learning Memory, история фактов, история встреч и стадия знакомства — отдельные поля одного learner aggregate.
Операция сохраняет before/result/request identity вместе со встречей и событием в одной Redis transaction.
Redis lock сериализует ход, CAS revision защищает от записи worker после истечения lease.
Дубликат request использует сохранённый ответ. Дубликат tool arguments в том же request использует сохранённый результат.
После падения финального LLM-вызова возвращается достоверный технический fallback по сохранённой встрече.
Поздний повтор старого request показывает актуальную версию встречи, а не устаревшее подтверждение.

История модели: до 40 сообщений, максимум 24 000 символов; профиль выбирает до 20 фактов каждого типа,
приоритет у цели, трудностей, уровня, текущего пояса и фактов по текущей теме. Сводка обновляется через context tool.
Сырые реплики, request/response receipts и сводка подчиняются прежним семи дням хранения; есть периодическая очистка.
Подтверждённые факты с источником, история исправлений и встречи сохраняются независимо от окна диалога.
Самооценка не превращается в измеренный уровень.

### Инструменты и время

Пять настоящих native tools: `get_trial_booking`, `create_trial_booking`, `reschedule_trial_booking`,
`cancel_trial_booking`, `update_learner_context`. JSON Schema строгие; сервер повторно проверяет типы и enum.
Модель не задаёт владельца встречи. Проверяются ID, version, активный статус, источник фактов в текущем сообщении,
точная дата, IANA/UTC offset, будущее время и DST. Есть режимы now/local/shift_minutes/same_time_on_date.
Одна активная запись на learner; перенос сохраняет ID, повторная запись после отмены связывается с предыдущей.
Прошедшая заглушка не становится missed/completed. Пробная встреча — около 15 минут, без слотов занятости AI.

Разбор естественного намерения, городов и относительных дат выполняет модель по исходному времени сообщения.
Код не маршрутизирует беседу по ключевым словам. Семантическую правильность такого разбора нужно проверять живой моделью.
Предел одного model/tool цикла — 55 секунд, до 6 model rounds и до 5 tool calls на round.
Сбой не создаёт фиктивного успешного результата.

### Telegram-карточка

Код сериализует `disabled: {}` у InlineKeyboardButton; основание исходной реализации:
[официальный контракт](https://core.telegram.org/bots/api#inlinekeyboardbutton).
У установленного tgbotapi 36.1.0 этого типа нет. `TutorCardRequest` использует штатный `SimpleRequest`
с точной сериализацией поля `disabled`; URL, callback_data и web_app отсутствуют.
В payload кнопки «Открыть урок» нет действия. Принятие payload и отображение в живом Telegram ещё не проверены.

ID карточки сохраняется в AI store. Перенос и отмена редактируют прежнюю карточку, включая рестарт Ktor.
Если Telegram запрещает редактирование, отправляется актуальная карточка. При отмене клавиатура пуста.
`message is not modified` считается успешным повтором. Ошибка доставки не отменяет сохранённую встречу.
При неопределённом сетевом исходе Telegram send/receipt остаётся стандартное окно возможного дубля сообщения;
повторное выполнение booking tool при этом исключается.

### Удалённый путь и совместимость

Удалены voice onboarding service, begin/checkpoint/results, его clip job поля/DTO, warmup/STT/TTS orchestration,
onboarding mode prompts, callbacks, call gate, автоматическое приглашение и subscription preview.
Старые onboarding state/actions/legacy-invitation endpoints отсутствуют; campaign claim/report больше не доступны. `GET /internal/campaign/legacy-onboarding` сохранён для исторического отчёта (может актуализировать excluded statuses, но не отправляет сообщения).
Старые onboarding followups не отправляются; явные reminder-функции независимых возможностей сохранены.

Общие зависимости вынесены в `LearnerStore`, `LearnerModel`, `review_content.py`, `review_score.py`, `ReviewMessages.kt`.
Calls/reviews/vocabulary/person memory переподключены к ним. Исторические assessment/learner ключи читаются;
миграции, исторические данные и аналитические отчёты не переписаны. Переноса пользователей нет.
Имена существующих `ONBOARDING_REVIEW_MODEL`/fallback env сохранены для совместимости конфигурации обычных reviews.

### Карта кода

| Ответственность | Источник |
| --- | --- |
| Prompt, native tool loop, fallback | [tutor.py](../../ai-service/app/tutor.py) |
| Схемы, intent, время, операции | [tutor_tools.py](../../ai-service/app/tutor_tools.py) |
| Redis, CAS, receipts, retention, analytics | [tutor_store.py](../../ai-service/app/tutor_store.py) |
| Wiring и HTTP routes | [main.py](../../ai-service/app/main.py) |
| Личность | [kora_personality.py](../../ai-service/app/kora_personality.py) |
| Профиль существующих звонков | [learner_store.py](../../ai-service/app/learner_store.py) |
| Ktor DTO и HTTP | [TutorClient.kt](../../cmp/server/src/main/kotlin/com/eliteteam/speakingcoach/ai/TutorClient.kt) |
| Ответ, typing, карточка и receipts | [TutorTelegramChat.kt](../../cmp/server/src/main/kotlin/com/eliteteam/speakingcoach/telegram/TutorTelegramChat.kt) |
| Приоритет команд и независимых функций | [TelegramHandlers.kt](../../cmp/server/src/main/kotlin/com/eliteteam/speakingcoach/telegram/TelegramHandlers.kt) |

Обычный текст попадает к Саше после обработчиков явных команд, активного Vocabulary Builder,
выбора ситуации и ввода времени `/remind`. Эти существующие функции не стали tools репетитора.

### Проверка

[Состояние и журнал доказательств](../status.md): автоматические тесты, частичные модельные пробы,
DEV и открытая приёмка. Эти результаты не являются проверкой текущего PROD.

## Telegram: текущая маршрутизация

Источник — [TelegramHandlers.kt](../../cmp/server/src/main/kotlin/com/eliteteam/speakingcoach/telegram/TelegramHandlers.kt).
[SP-01](tutor-chat.md#sp-01-постоянный-текстовый-чат-с-сашей) — основной текстовый путь. Существующие voice-функции описаны отдельно.

### Команды и сообщения

| Вход | Обработчик |
| --- | --- |
| `/start`, `/onboarding`, включая `/start@bot` и payload | Начать/продолжить постоянный чат Саши; память и trial сохраняются |
| Обычный текст | TutorRuntime после обработчиков явных независимых функций |
| Голос в обычном чате | Игнорируется; STT/TTS не запускаются |
| Голос в открытом call | Старый clip pipeline |
| `/words` и активный Vocabulary Builder | Собственный текстовый/голосовой workflow |
| `Start call`, выбор ситуации | Независимый call; старый onboarding/score/goal gate удалён |
| `/profile`, `/streak`, `/voice`, `/speed`, `/remind` | Сохранённые обработчики прежних возможностей |
| Текст при ожидающем `/remind` | Разбор времени напоминания; не trial timezone |
| Другой контент | Игнорируется |

Саша отвечает по-русски, показывает `typing`, убирает старую reply-клавиатуру.
Trial-карточка формируется из сохранённой встречи. В её payload нет URL/callback/web_app;
отображение неактивной кнопки живым Telegram ещё не принято. Детали delivery — в контракте SP-01.
`/onboarding` больше не открывает voice assessment. Старые onboarding callbacks не поддерживают прежний путь.

### Webhook и голосовая доставка

Webhook проверяет `X-Telegram-Bot-Api-Secret-Token`; неверный секрет даёт 403.
После принятия update возвращается 200. Ошибка последующей обработки не заставляет Telegram повторить уже подтверждённый update.
`RedactingKSLog` скрывает bot token и URL с токеном. Для voice queue — один обработчик на session и максимум три клипа.
`HttpClipClient` опрашивает job каждые 1.5 секунды до 90 секунд.
В открытом call бот показывает `record_voice`, перед карточкой исправления — `typing`, затем снова `record_voice`.
Для файла больше 20 MB или ошибки `file is too big` просит короткие части; повтор того же файла не предлагает.
Session ID связывается с Telegram chat; TutorStore и LearnerStore используют его независимо.


## Совместимость сохранённых систем

SP-01 заменил voice onboarding постоянным текстовым чатом. [Текущий контракт](tutor-chat.md#sp-01-постоянный-текстовый-чат-с-сашей)
и [статус](../status.md) важнее ранних продуктовых планов.

### Что удалено из пользовательского пути

Нет приглашения Let’s chat, голосовых порогов 30/120 секунд, onboarding begin/checkpoint/results,
цепочки выбора daily goal после знакомства, старого call gate, campaign claim/report и subscription preview.
`/start` и `/onboarding` теперь продолжают один tutor conversation, а не сбрасывают attempt.
Урок не запускается; старые цены превью не определяют будущую подписку.

`GET /internal/campaign/legacy-onboarding` оставлен для просмотра статуса старой кампании.
Внутри `campaign_status` может помечать ещё не отправленных получателей `skipped_completed` по старым assessment.
Это не endpoint отправки: campaign runner в Ktor равен `null`. Исторические `pending`/`uncertain` не доказывают доставку.

### Что сохраняется

- `LearnerStore`, `LearnerModel`, `review_content.py`, `review_score.py` обслуживают calls, reviews и vocabulary.
- Исторические `onboarding:*`, `assessment:*`, `practice-goal:*`, `learner:*`, campaign и PostgreSQL-таблицы читаются для совместимости.
  Применённые Flyway-миграции не переписываются. Удаление документов не удаляет данные.
- Оценки старых reviews используют CEFR/band/position/shade; это не пять показателей нового продукта.
  [Profile](voice-and-reviews.md#telegram-progress-profile), [коррекции](voice-and-reviews.md#коррекции-речи-в-сохранённых-calls) и
  [workflow оценки](voice-and-reviews.md#vocabulary-assessment-workflow) описывают сохранившуюся часть.
- В `Application.kt` followup stages старого onboarding получают `retired`, soft nudge для onboarding — `false`.
  Ветка `practice`, настроенный `/remind` и soft nudge открытого call остаются.
  SP-01 не добавляет напоминания о trial. Нельзя считать весь старый scheduler отключённым.
- [Onboarding analytics](analytics.md#onboarding-analytics) и отчёты reminders сохраняют старые когорты,
  стадии и delivery outcomes. Они не становятся аналитикой Саши автоматически.
- `ONBOARDING_REVIEW_MODEL` и fallback env сохраняют имена для review-совместимости.
- Прототип CMP использует собственный app API и WebRTC. Его макеты Releva сохраняются как материалы прототипа,
  а не как дизайн будущего Mini App.

### Проверки удалённого onboarding

Для сохранения границы доказательств: voice onboarding 2026-09-28 имел 95 Python tests и Kotlin tests;
conservative screening — 118 Python tests и Kotlin test/detekt; token-limit revision 2026-10-08 — 297 Python tests.
Incomplete nudge 2026-10-06 проходил Kotlin/detekt и PostgreSQL integration test.
Модельная точность, новая переписка Саши и текущие окружения этими проверками не подтверждаются.
Полные результаты прежних реализаций остаются в Git на коммите `60d4085`.
