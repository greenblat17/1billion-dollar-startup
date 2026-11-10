# Vocabulary Builder и напоминания

Контракты базовой поставки SP-01 и сохранённого legacy-кода. Новый фундамент SP-00 использует PostgreSQL;
legacy сохраняется отдельно, без переноса его Redis-хранилищ и без обязательного подключения старых функций к новому runtime.
Готовность изоляции и переключения текущего чата проверяется по [статусу](../status.md).
Описание legacy API ниже не означает, что эти маршруты доступны в новом runtime.

- [Vocabulary Builder: контракт существующего кода](#vocabulary-builder-контракт-существующего-кода)
- [Существующие reminders и streak](#существующие-reminders-и-streak)

## Vocabulary Builder: контракт существующего кода

Это независимая сохранённая функция, не обязательная программа нового репетитора и не SP-01 trial.
Реализация: [vocabulary_builder.py](../../ai-service/app/vocabulary_builder.py),
HTTP wiring — [main.py](../../ai-service/app/main.py), Telegram — `TelegramHandlers.kt`.

### Версии и переходы

Новые уроки используют format 5: Understand слова → Use того же слова → следующее слово → Word game → итог.
Сохранённые format 3/4 продолжают собственные ветки. Версия хранится на уроке; новый код не переигрывает начатый урок.
Именно для совместимости в коде остаются Recall, прежние категории и разные переходы.
Understand допускает `understood`, `confused`, `conversation`; Use — `used`, `natural_without_target`, `misuse`, `conversation`.
Открытие и hint не оценивают ученика. Schema ограничивает категории по фазе; сервер проверяет результат повторно.
Новый путь отдельно оценивает текущий ответ, затем строит вопрос выбранной следующей фазы.
Это устраняет конфликт: Understand называет выражение, Use ожидает самостоятельную фразу без подсказки целевого слова.
После последнего успешного Use запускается Word game; отдельный вопрос следующего слова не генерируется.
Вопрос или просьба объяснить задание не считаются ошибочной попыткой употребления.
Хороший ответ без target не переводится в `used`; шаг продолжается без лимита неудачных ответов.
`matchedPhrase` должен подтверждаться текущим транскриптом; голое повторение слова не считается собственной фразой.

### Контекст и доставка

Журнал урока сохраняет вопросы, ответы, hints, оценки и смены фаз.
До лимита запрос содержит историю урока; после — summary и доступные дословные события.
При росте одного шага его ранние реплики тоже переходят в summary. Текущий вопрос и новый ответ остаются в полях запроса.
Исходный журнал не удаляется из-за сжатия; временная ошибка не продвигает фазу.
`VOCABULARY_CONTEXT_WINDOW_TOKENS` задаёт бюджет; minimum 4096, резерв ответа 1024.
Wiring задаёт 1 000 000 для настроенной Claude Sonnet 4.6 либо консервативный fallback 8192.
Это настройка текущего кода, не гарантия размера окна любого провайдера: при смене модели требуется проверка.
Telegram message ID используется для повторной выдачи сохранённого ответа и подтверждения delivery.
Отправка Telegram и Redis receipt не атомарны: сбой между ними может повторить сообщение.

### Границы и диагностика

`/words` и активный lesson обрабатываются раньше обычного tutor text/voice routing.
Эти записи не импортируются автоматически в TutorStore и roadmap.
Метрики, текущие фазы и retention: [vocabulary analytics](analytics.md#vocabulary-builder-analytics).
Отдельно существует [Vocabulary enrichment в call review](voice-and-reviews.md#vocabulary-assessment-workflow): это не Builder lesson.
Локальные schema/store-тесты не подтверждают естественность вопроса, полезность summary или качество живого урока.

## Существующие reminders и streak

Эти механизмы обслуживают сохранённую voice-практику. У SP-01 нет напоминаний о trial.
Источник wiring — `cmp/server/src/main/kotlin/com/eliteteam/speakingcoach/Application.kt`.

### Настроенное время

`/remind` сохраняет `HH:MM` в Europe/Moscow, независимо от пояса trial-записи.
`Stop reminders` удаляет время. Redis-ключ `reminder-time:{sessionId}` не имеет TTL.
`ReminderRunner` работает в webhook scope; авто-раунд идёт раз в минуту.
Claim исключает уже практиковавшихся в московский день и уже получивших claim.
Окно автоотправки — два часа после выбранного времени, без переноса через полночь.
Ручной режим игнорирует часы, но сохраняет daily claim; он не означает разрешение агенту отправлять сообщения.
`reminder:sent:{day}:{sessionId}` использует SET NX и TTL два дня.
Отчёт различает `sent`, `blocked`, `failed`; сетевой исход не доказывает прочтение.
HTTP payload и Redis-агрегаты: [run-and-ci](operations.md#run-locally-env-ci).

### Сохранённая practice-ветка

`FollowupRunner` принимает только stage `practice`; прежние onboarding stages получают `retired`.
Сохранённые preference и ledgers определяют право на отправку; новый tutor-chat их не создаёт автоматически.
Для старых eligible practice-записей без заданного времени действует предел 14 отправок и финальное сообщение.
Явный отказ подавляет последовательность. Заданный `/remind` использует собственное время без второй отправки того же назначения.
Тайминг рассчитывается по входящим visits; детали хранения — [совместимость](tutor-chat.md#совместимость-сохранённых-систем).
Общие и персональные варианты чередуются по московскому дню и пользователю.
`ai-service/app/reminder_copy.py` использует подтверждённые факты/реплики; невалидная персонализация даёт общий вариант.
В аналитику попадает фактический copy kind, а не приватный текст.

### Кнопка и открытие call

`ReminderButtonStore` хранит вопрос открытия для конкретного принятого Telegram-сообщения.
Новая принятая отправка заменяет активную кнопку атомарно в PostgreSQL; старая callback отклоняется сервером.
Удаление старого markup повторяется отдельно; ошибка редактирования не активирует старую кнопку обратно.
При неудаче новой отправки прежняя кнопка сохраняется. В базе вопрос остаётся только у активной кнопки.
Кнопка открывает free call с парным вопросом; обычный `Start call` выбирает собственный старт.
Активный call не заменяется. Неудачная доставка стартового вопроса не должна переносить его в следующий call.

### Soft nudge

`SoftNudgeRunner` проверяет открытый call после пяти минут тишины с последнего доставленного голоса бота.
Не более одного durable claim на call; входящее событие перепроверяется перед отправкой.
Рассматриваются ответы последних 24 часов; неясная доставка не повторяется автоматически.
Onboarding-ветка возвращает `false`. Это отдельный ledger, не расходующий daily reminder slot.
Telegram не сообщает боту, что ученик ещё записывает длинное голосовое: эта неопределённость остаётся.

### Streak

`ai-service/app/streaks.py` считает завершённые voice-ходы по Europe/Moscow, не текстовые сообщения Саше.
Показанный streak равен current, если последний день — сегодня или вчера; иначе нулю. Best сохраняется.
Ключи: `streak:user:{session}`, `streak:users`, маркер backfill `streak:backfill:v1`.
`GET /internal/streak/{sessionId}` возвращает current, best и last7.
`/streak` показывает неделю; при ошибке рендера изображения остаётся текст.
Метрики и знаменатели: [reminder effectiveness](analytics.md#reminder-effectiveness-dashboard),
[технический контракт](analytics.md#регулярность-speaky). Их конверсия не является конверсией tutor trial.

### Сохранённые свидетельства проверок

В ревизии nudge analytics от 2026-10-19 прошли 219 Kotlin tests без failures/errors/skips,
detekt и JDBC-проверки на временном PostgreSQL 14. Проверялись delayed acknowledgement
возле семидневной границы и practice после non-primary attempt.
Ревизии timing/stage followups от 2026-10-20 прошли server/PostgreSQL checks.
Эти результаты не подтверждают текущую отправку, Telegram UX или rollout; live acceptance тогда оставалась открытой.

### HTTP schedule, claim и report

Daily reminder (plan: `integrations/practice.md`). `POST /internal/reminders/schedule` (needs `X-Internal-Token`) takes `sessionId`, `action` (`ask`, `decline`, or `submit`), optional `runId` and `text`. `ask` stores `reminder-pending:{sessionId}` as the run id. `submit` accepts `H:MM` or `HH:MM`, stores zero-padded `reminder-time:{sessionId}` with no TTL, and clears the pending key. A bad time stays pending and returns `{"status":"invalid"}`. Text with no pending key returns `{"status":"ignored"}`. `decline` clears pending and does not create a time. `POST /internal/reminders/claim` takes `{"mode":"auto"|"manual"}` and returns `{"targets":[{"sessionId":"tg-123","name":"Alex Green"|null,"streak":0}]}`. `streak` is the shown streak (0 when the field is missing). Targets are only sessions with `reminder-time`, `tg-<number>` or the live form `tg-ChatId(chatId=<number>)`, minus today's Moscow `metrics:dau:{day}`. Auto also requires the Moscow clock to be inside the saved time and the following two hours, on that same date. Manual ignores the clock. Each returned session is marked with `reminder:sent:{day}:{sessionId}` (`SET NX`, TTL 2 days), so a second call on the same Moscow day returns only sessions not yet claimed. Ktor ticks every minute in webhook mode and sends one English text per auto target. An empty auto claim is not reported. A shown streak of 2 or more picks the streak template pool. A manual run from the dashboard uses `mode: manual`. The "send to myself" button loads that chat's streak when the template is "today". The dashboard forecast counts this opt-in queue, not yet claimed today.

`POST /internal/reminders/report` (token, JSON) body: `{"mode":"auto"|"manual","startedAt":"ISO","finishedAt":"ISO","claimed":N,"results":[{"sessionId":"tg-1","templateId":"day_went","status":"sent"|"blocked"|"failed"}]}`. Unknown modes become `auto`, unknown statuses `failed`, template ids outside `[a-z0-9_]{1,64}` become `unknown`. ai-service stores `reminder:day:{day}`, `reminder:tpl:{id}` + `reminder:templates`, `reminder:last:{session}`, `reminder:ignored:{session}`, `reminder:reply_seconds:{day}` (last 1000), `reminder:runs` (last 30). `/internal/funnel/voice` counts a reply when it lands within 24 hours of the last sent reminder.

`GET /internal/metrics` adds `reminders: {today, week, days[14], segments[new|active, 7 days], templates, replyMedianSeconds|null, runs, autoToday|null, forecast}` and, in each `chats` row, `lastReminderAt` (ISO or null) and `reminderIgnored`. It also adds `streaks: {buckets, reminderBuckets, retention}` (plan: `integrations/practice.md`). `retention.cohorts` is the last 8 Monday–Sunday Moscow weeks by activation day; `d1` / `d7` / `d30` are fractions or null. Ktor DTO fields default, so an older ai-service without the block still renders.

Streaks (same plan). A finished clip's `result.streak` is `{current, best, firstToday, firstEver, newRecord}` or absent. `GET /internal/streak/{sessionId}` returns `{current, best, last7}`. Redis: `streak:user:{session}`, `streak:users`, `streak:backfill:v1`, `streak:released_day`. Reminder day hashes also store `sent_streak_{bucket}` and `returned_streak_{bucket}`. Deploy ai-service before cmp-server.


### Тайминг сохранённой practice-ветки

`FollowupTiming.kt` группирует входящие события в visits с интервалом 30 минут и использует историю за 30 дней.
Предпочтительное UTC-время — медиана наиболее плотного двухчасового окна; при равенстве учитывается свежесть визита.
Одиночный визит даёт собственное время. Требуются 24 часа неактивности, окно догоняющей отправки — два часа.
V17 хранит schedule, версию политики, `scheduled_for` и dirty/recompute marker.
Daily claim остаётся не более одного на московский день; неизвестная доставка не считается подтверждённой.
Это сохранённая механика прежней practice-ветки, не разрешение присылать приглашения trial.
Текущие правила practice: [practice reminders](practice.md#существующие-reminders-и-streak),
[метрики](analytics.md#reminder-effectiveness-dashboard).
