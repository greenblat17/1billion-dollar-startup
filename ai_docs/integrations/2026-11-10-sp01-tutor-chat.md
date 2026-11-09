# SP-01: постоянный текстовый чат с Сашей

Реализация в рабочей директории, без деплоя. Контракт: [утверждённый план](../plans/2026-11-09-sp01-text-onboarding.md).
Полная [v0.5](../product/2026-11-08-ai-tutor-experience-v0.5.md) остаётся направлением будущего продукта.

## Реализованный путь

```text
Telegram /start, /onboarding, свободный текст
  → TutorClient → /internal/tutor/start|turn
  → TutorRuntime → native LLM function calls → проверка → TutorStore
  → LLM получает фактический результат → русский ответ
  → Telegram reply + карточка только при изменении встречи
```

Один conversation ID переживает знакомство, запись, отказ, отмену, повторный `/start` и рестарт.
Никакой обязательной анкеты или терминального состояния чата нет. Приветствие генерирует модель.
Личность выделена из Kora в `kora_personality.py`; исходный free-call prompt собирается с тем же текстом личности.
Чат использует настроенную модель Kora H, а при отсутствии отдельного Kora model adapter — общий LLM с той же личностью.
Текст любого языка передаётся модели; отвечает Саша по-русски. Голос в обычном чате не запускает STT/TTS.
Явно открытые прежние calls и Vocabulary Builder сохраняют свои обработчики и голосовые возможности.
Их наличие не превращает trial-заглушку в реальный звонок.

## API и данные

Все маршруты защищены существующим `X-Internal-Token` middleware.

- `POST /internal/tutor/start`, `POST /internal/tutor/turn`: `sessionId`, `requestId`, `text`, `receivedAt` (Unix seconds).
  Telegram adapter передаёт исходное `message.date`, не время ответа LLM.
- Ответ: `conversationId`, `reply`, `booking`, `bookingText`, `degraded`, `replyMessageId`, `cardMessageId`.
  `booking` отсутствует на обычных ходах; карточка не повторяется автоматически.
- `POST /internal/tutor/delivery`: подтверждает отправленный reply/card, сохраняет Telegram message ID и версию встречи.
- `GET /internal/learners/{sessionId}/context`: общий Personal/Learning context, сводка, trial preference и текущая встреча.
  Контракт доступен будущему lesson runtime; сам lesson runtime не реализован.
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

## Инструменты и время

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

## Telegram-карточка

На момент проверки Bot API поддерживает `disabled: {}` у InlineKeyboardButton:
[официальный контракт](https://core.telegram.org/bots/api#inlinekeyboardbutton).
У установленного tgbotapi 36.1.0 этого типа нет. `TutorCardRequest` использует штатный `SimpleRequest`
с точной сериализацией поля `disabled`; URL, callback_data и web_app отсутствуют.
Кнопка «Открыть урок» действительно не задаёт действие. Это не кликабельная имитация disabled-кнопки.

ID карточки сохраняется в AI store. Перенос и отмена редактируют прежнюю карточку, включая рестарт Ktor.
Если Telegram запрещает редактирование, отправляется актуальная карточка. При отмене клавиатура пуста.
`message is not modified` считается успешным повтором. Ошибка доставки не отменяет сохранённую встречу.
При неопределённом сетевом исходе Telegram send/receipt остаётся стандартное окно возможного дубля сообщения;
повторное выполнение booking tool при этом исключается.

## Удалённый путь и совместимость

Удалены voice onboarding service, begin/checkpoint/results, его clip job поля/DTO, warmup/STT/TTS orchestration,
onboarding mode prompts, callbacks, call gate, автоматическое приглашение и subscription preview.
Старые state/actions/legacy-invitation endpoints отсутствуют; campaign claim/report больше не доступны.
Старые onboarding followups не отправляются; явные reminder-функции независимых возможностей сохранены.

Общие зависимости вынесены в `LearnerStore`, `LearnerModel`, `review_content.py`, `review_score.py`, `ReviewMessages.kt`.
Calls/reviews/vocabulary/person memory переподключены к ним. Исторические assessment/learner ключи читаются;
миграции, исторические данные и аналитические отчёты не переписаны. Переноса пользователей нет.
Имена существующих `ONBOARDING_REVIEW_MODEL`/fallback env сохранены для совместимости конфигурации обычных reviews.

## Проверка и точные пробелы

Автоматические проверки охватывают lifecycle, дубликаты, конкурентные Redis-совместимые workers, CAS,
исправление памяти, DST, UTC offsets, API auth/validation, сохранение ответа/карточки, delivery receipts,
продолжение после отказа/записи, cohort conversion и ограничение контекста.
Redis-тесты используют fakeredis с Lua, не удалённый production Redis.

`evals/tutor_dialogues.py` — воспроизводимый opt-in probe реальной модели на синтетических учениках,
без Telegram и production store. Проверяет диалоги из плана; результат требует оценки смысла и действий человеком.
Первоначально credentials отсутствовали. Затем ключ OpenRouter передан через защищённую форму вне репозитория.
Живой запуск выявил несовместимый `parallel_tool_calls=False`: все endpoints настроенной Claude Sonnet 4.6
отфильтровывались с HTTP 404 при `require_parameters`. Параметр удалён; последовательное исполнение
инструментов обеспечивает runtime. Строгие tool schemas и финальный response schema сохранены.
Источник возможностей: [metadata endpoints](https://openrouter.ai/api/v1/models/anthropic/claude-sonnet-4.6/endpoints).
Локальный SDK MockTransport перехватывает фактический HTTP JSON для обычного URL и OpenRouter:
`parallel_tool_calls` отсутствует, native tools/strict schemas сохранены, OpenRouter получает `require_parameters=true`.

После исправления первые пять ходов получили реальные ответы. Модель объяснила confident/confidence,
сохранила профессию, страх ошибок и текущий пояс, создала встречу на завтра 19:00 Europe/Berlin,
продолжила обсуждение страха после записи. Перенос на час позже сохранил ID и увеличил version до 2.
После переноса провайдер вернул HTTP 402 `in_flight_budget_exhausted` с Retry-After 120;
технический ответ правильно показал фактически сохранённое время 20:00. Остальные ходы не прошли.
Это частичное live evidence, не успешная полная диалоговая приёмка.
В ответах замечено техническое слово «плейсхолдер»; prompt уточнён: объяснять недоступность урока простыми словами.
Probe теперь прекращается при degraded-ответе с ненулевым exit code вместо ложного успешного завершения.
Telegram token не настроен. Визуальный disabled markup и Telegram acceptance не проверены.
Нет деплоя, push или PR. Итоговые команды и количества проверок записаны в плане.


### Разбор последующих прогонов и локальное исправление intent

| Прогон | Что наблюдалось | Что не подтверждено |
| --- | --- | --- |
| После удаления несовместимого параметра | 5 обычных ответов; create, update context и reschedule действительно изменили store. Перенос сохранил ID, version=2. | На финальном ответе переноса начались ошибки; диагностический запрос вернул 402 in_flight_budget_exhausted. Остальные 19 из 24 ходов дали degraded, включая перенос. |
| Повтор с паузой 30 секунд между ходами | 3 обычных ответа; затем create сохранил запись 19:00, ответ был фактическим fallback. | Probe остановлен на четвёртом ходе. Тип этой ошибки не записан старым logger; нельзя уверенно приписать её 402. Следующий диагностический запрос прошёл. |
| Отдельный сценарий «сейчас → отказ» с паузами | Приветствие и ответ на просьбу «Хочу урок прямо сейчас». Модель спросила «Забронировать всё равно?», встреча НЕ создана. | На отказе получен APIStatusError 402, probe остановлен. Продолжение после отказа не проверено. |

Ни один полный сценарий не завершён. Живые cancel/rebook, неоднозначные переносы, смешанный запрос
«перенеси + исправь цель + ответь на вопрос», возвращение через /start и разговор после отказа остаются открытыми.
Живые наблюдения относятся только к синтетическим learners в локальном store, не к Telegram или production Redis.

Причина лишнего подтверждения: prompt одновременно требовал немедленного выполнения и объяснения
недоступного запуска «до согласия на trial». Модель превратила последнее требование в confirmation gate.
Локально это противоречие устранено: ясная просьба об уроке сейчас разрешает запись в том же ходе;
недоступность запуска объясняется вместе с фактическим результатом, а не отдельным запросом согласия.
Tool description и описание `intent=explicit_request` согласованы с этим правилом.
Гипотеза, обсуждение возможности или неоднозначное время по-прежнему требуют ответа/уточнения без записи.
Ключевые слова в коде не используются для маршрутизации. Проверка типа intent и verbatim evidence осталась
на сервере; семантическую классификацию делает модель. Enum сам по себе не доказывает верность классификации.

Eval содержит отдельные случаи «Хочу урок прямо сейчас» и переход от гипотезы/вопроса к явному поручению.
Он проверяет реальное наличие now booking либо отсутствие записи, добавляет критерии проверки ответа человеком
и возвращает ошибку при несоответствии. Локальные тесты подтверждают, что инструмент не требует второго согласия,
пояса или профиля для now, а неподдерживаемые intent labels отклоняются. Они НЕ подтверждают поведение модели.
После этого исправления платных вызовов и Telegram-проверок не было, по поручению родителя.

Локальный полный Python suite после исправлений: **521 passed**, одна Starlette/AnyIO deprecation warning.
Kotlin не менялся в этом продолжении; прежние **277 tests**, fat JAR и detekt остаются результатом предыдущего запуска.

### Подготовка draft PR и DEV

Пользователь разрешил commit/push/draft PR и Redeploy DEV обоих компонентов; PROD и merge не разрешены.
Pre-deploy review: Ktor DTO соответствуют AI endpoints; KoraHModel наследует native complete_tools;
в OpenRouter wiring используется KORA_MODEL, а при другом base URL — общий LLM с личностью Kora.
Ссылок runtime на удалённые onboarding classes/endpoints не найдено. Общие review/call зависимости сохранены.

Redeploy DEV теперь проверяет SSH identity по сохранённым ed25519 host keys (`infra/dev/known_hosts`),
печатает прежний image ID и сравнимые artifact digests. PROD-путь не менялся.
После обоих deploy jobs отдельный job проверяет контейнеры, startup markers, health, CMP→AI internal auth,
готовность Redis/model config без значений секретов и username DEV-бота через getMe (без отправки сообщений).
Опциональный `tutor_smoke` ограничен двумя синтетическими запросами: now/cancel. На любом degraded-ответе
он прекращает вызовы без ретраев. Остановка smoke явно отмечается в логе; зелёный readiness job не означает acceptance.

Последний успешный Redeploy DEV до этой работы: run 41915810918, оба компонента
`5032a0aa0768e4ad70c0d593f5ba5080577d0f7a`, ветка `bb/f-pay-thr_c38nqsywqm`.
Это ревизия для восстановления через Redeploy DEV; volumes Redis/Postgres не сбрасываются.


### DEV: фактическая выкатка 2026-11-10

Draft PR: https://github.com/greenblat17/1billion-dollar-startup/pull/52.
DEV run: https://github.com/greenblat17/1billion-dollar-startup/actions/runs/42706735998.
Оба deploy jobs и readiness завершились успешно на `c148e31ab3afd67a2ac790794be4ccd0ab97dcc2`.
Деплой был запущен, пока эта подсессия ожидала CI; повторный deploy не запускался.

Проверенные факты из runtime: оба контейнера running, 0 restarts, OOM=false; AI и CMP health=200,
CMP→AI health=200, authenticated learner context=200. OPENAI_API_KEY/AI_INTERNAL_TOKEN/REDIS_URL
на AI присутствуют (значения не раскрывались). DEV bot getMe вернул `vlama_bot`.
JAR digest на runner и внутри CMP совпал: `94ed4803c13c0bfb06b40e386bc4b14f356193568151c8ed2cb0beca1c37be67`.
Digest всего дерева app/*.py на runner и внутри AI совпал: `08235f6eaf76f6470df10ef6c750ab11ebbe0a504b9d701a1cfd0d64a59dba40`.
Предыдущие image IDs записаны в deploy log до замены контейнеров. Redis/Postgres не сбрасывались.

В startup markers AI ошибок нет; CMP показал один ERROR при успешном Application started и health.
Его причина требует отдельной проверки. Smoke модели в этом workflow не запускался, Telegram acceptance открыта.
Пользователь может проверять `/start` → «Хочу урок прямо сейчас» → вопрос по английскому → перенос → отмена
в https://t.me/vlama_bot. Кнопка урока должна быть неактивной; после отмены диалог продолжается.

CI приложения прошёл, кроме macOS packaging: GitHub отверг runner `macos-14-arm64-us-default`
до запуска кода. Исправление использует `macos-15`, уже работающий для iOS в этом же CI.
В Redeploy DEV добавлен `verify_only` для проверки без пересборки/рестарта. В этом режиме verification_ref
обозначает ревизию проверяющего скрипта, а не заявленную ревизию работающего приложения.
