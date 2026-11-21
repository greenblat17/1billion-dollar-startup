# Архитектура Speaky

Единственный справочник реализованной архитектуры прежних SP-00–SP-08 и новых SP-02–06/SP-12A/SP-12B/SP-13A/SP-17A/SP-17B v5.5.
Он описывает код рабочего дерева, а не текущую конфигурацию сервера.
Результаты локальных проверок и границы готовности — в [статусе](ai_docs/status.md),
команды оператора — в [эксплуатации](ai_docs/integrations/operations.md#sp-00-новый-runtime-и-перенос-текущего-чата).

## Компоненты и границы

```mermaid
flowchart LR
  tg[Telegram] <-->|webhook / Bot API| ktor[Ktor: SpeakyApplication]
  ktor <--> pg[(PostgreSQL: foundation)]
  ktor -->|tutor turn / compute| ai[FastAPI: speaky_main]
  ai -->|scoped commands / snapshots| ktor
  ai <--> llm[LLM provider]
  tg -->|открывает| mini[Mini App: nginx / Web Audio]
  mini <-->|HTTPS API / WSS PCM| ai
  ai <-->|speech-to-speech| rt[OpenAI Realtime]
  ai -->|archive before forwarding| s3[(Private S3)]
```

Три поставляемых компонента: Ktor, FastAPI и отдельный статический Mini App frontend.
Foundation, Text Tutor, Context Builder и Learning Engine — модули backend/AI.
Отдельных сервисов для каждого логического блока нет. Redis не входит в новый runtime.

| Компонент | Ответственность |
| --- | --- |
| Ktor / foundation | Единственный владелец состояния ученика, бизнес-операций, истории, фоновых задач и контроля доступа |
| PostgreSQL | Student/identity, profile/goal/memory, Lesson/Call Session, Schedule, payment/entitlement, Roadmap, Plan/Evaluation, history, jobs и receipts |
| Telegram adapter | Проверка webhook, сохранение private-text входа, запуск хода, отправка ответа и учёт результата доставки |
| Python FoundationTutor | Диалог Саши и native tools; все изменения выполняются через Ktor |
| Python ContextBuilder | Ограниченные проекции общего состояния для text, voice и learning; чтение не меняет профиль |
| Python LearningEngine | Вычисление результатов фоновой работы; применение результата контролирует backend |
| LLM | Формулировка ответа, выбор tools и интерпретация контекста; не владелец состояния и прав доступа |

### Точки входа

- Kotlin [`Application.kt`](cmp/server/src/main/kotlin/com/eliteteam/speakingcoach/Application.kt) вызывает
  [`SpeakyApplication.kt`](cmp/server/src/main/kotlin/com/eliteteam/speakingcoach/SpeakyApplication.kt).
  Он подключает foundation, worker, `/health` и Telegram webhook при соответствующей конфигурации.
- AI Docker запускает [`app.speaky_main:create_app`](ai-service/app/speaky_main.py).
  Этот root создаёт FoundationClient, ContextBuilder с MemorySelector, FoundationTutor, LearningEngine и Realtime routes.
- Frontend собирается из [`mini-app/src/`](mini-app/src/) и раздаётся отдельным nginx-контейнером.
- Ktor требует PostgreSQL и завершённый import marker. Без marker новый backend не стартует.
  Это защищает существующий чат от случайного запуска с пустым профилем.

Формат диагностических событий, владельцы записей и связь между попытками описаны в [архитектуре логирования](ai_docs/architecture/runtime-logging.md).

## Ход текстового диалога

1. Webhook проверяет секрет Telegram. Private-text update сохраняется до HTTP ACK.
2. Backend связывает Telegram identity с Student и обрабатывает входы по порядку.
   Lease/fence защищают обработку от конкурирующего или просроченного исполнителя.
3. Ktor создаёт ограниченный execution scope и вызывает AI tutor turn.
4. ContextBuilder читает backend snapshot. Модель получает общий профиль, цель и ограниченную историю.
5. Native tools вызывают backend commands. Backend проверяет владельца, scope, источник, версии и idempotency.
6. Результат операции и reply сохраняются. После сбоя используется сохранённый результат;
   уже выполненная запись не становится новой операцией.
7. Telegram adapter отправляет ответ и фиксирует исход. Неизвестная доставка не повторяется вслепую.

Успешный ACK означает сохранённый вход, а не готовый ответ. Успех tool подтверждается backend receipt,
а не фразой модели. Внешняя доставка Telegram не имеет гарантии exactly-once.
Правила беседы и trial остаются в [продуктовом поведении SP-01](ai_docs/plans/2026-11-09-sp01-text-onboarding.md).

## Данные, память и общий контекст

Общий профиль принадлежит Ktor/PostgreSQL. Python не поддерживает вторую изменяемую копию профиля.
Text, Voice и Learning используют одну идентичность и проекции одного состояния через ContextBuilder.
Голосовой runtime использует этот контекст после admission; качество передачи контекста требует живой приёмки.

- Заявленные пользователем факты, самооценка и учебные observations разделены.
  Observations сохраняют источник, версию анализа и неопределённость.
- Личные факты, предложенные AI, остаются скрытыми candidates до подтверждения источником пользователя.
- Контекст ограничен бюджетами; модель не получает всю историю без ограничений.
- Новая сырая история хранится 30 дней. Копирование не продлевает срок источника.
  Импортированная история TutorStore сохраняет прежние семидневные deadlines.
- Forget/delete очищают данные нового пути и отзывают старые scopes/jobs через epoch.
  Forget консервативно очищает raw history и производные копии, включая прежние значения цели;
  другие подтверждённые факты сохраняются.
- После восстановления backup до запуска применяется свежий внешний deletion ledger.
  Очистка сохранённых legacy-хранилищ этим механизмом не обещается.

## Фоновая работа и граница урока

FoundationJobs хранит очередь в PostgreSQL. Worker получает lease и fence, вычисляет результат вне DB-транзакции,
затем backend применяет его после проверки источника, epoch, зависимостей и версий.
Просроченный worker не может перезаписать актуальное состояние. Retry и диагностика доступны оператору.

Lesson/Call Session, Schedule, entitlement, Roadmap, Plan/Evaluation имеют backend-модель и операции.
Realtime получает общий контекст только после backend admission. Для regular отсутствующий или устаревший
обязательный план блокирует первый вход; trial admission использует встроенный briefing.

Shipping root использует `LessonPlanner` из `regular_lesson.py`: модель готовит для regular
отдельные objective/topic/strategy; trial сохраняет briefing. Backend применяет план с проверкой
версий и завершённости предыдущих анализов. Неверный ответ модели не заменяется фиктивным планом.
`TrialAnalyzer` сохраняет качественные наблюдения с источниками. `TrialWorker` материализует
расписание и отправляет уведомления через durable outbox. Настоящая оплата не реализована; персональный roadmap описан в SP-06 ниже.

## Внутренние API и доверие

| Направление | Интерфейс |
| --- | --- |
| Ktor → AI | `POST /internal/foundation/tutor/turn`, `POST /internal/foundation/compute` |
| AI → Ktor | `/internal/foundation/v1`: `POST /commands`, `GET /snapshot`, `/operations/{id}`, `/jobs`, `/diagnostics` |
| Проверка процесса | `/health` каждого сервиса |

Внутренние запросы используют `X-Internal-Token`; операции дополнительно связаны с `X-Execution-Scope`.
Scope выдаёт backend, а не модель. Service token сам по себе не разрешает произвольную запись за другого ученика.
Версии и receipts делают конфликт или повтор явным. Ошибка хранилища не переключает работу на in-memory или Redis.
Конфигурация, сетевые адреса и секреты перечислены только в [operations](ai_docs/integrations/operations.md).

## Почему устроено так

| Решение | Причина |
| --- | --- |
| Единый владелец данных в backend | У чата, голоса и learning одна цель и память; нет двусторонней синхронизации отдельных профилей |
| Характер и стратегия режима разделены | Общая Kora сохраняется; backend задаёт textMode, ContextBuilder добавляет только стратегию знакомства или обычного диалога |
| LLM выбирает tools, backend исполняет | Сохраняется свободный диалог; ownership, версии и успех операции не зависят от ответа модели |
| Durable приветствие и сброс | Каждый новый `/start` атомарно удаляет прежний профиль через deletion/epoch и создаёт нового Student; V47 хранит границу команды вне удаляемого профиля. Replay не сбрасывает повторно. Реплики имеют receipts, новый вход отменяет остаток до send claim; подтверждённые отправки входят в общую историю |
| Durable inbox, runs и receipts | Перезапуск не теряет подтверждённый вход и не повторяет уже выполненную бизнес-операцию |
| Compute вне транзакции | Медленный AI-вызов не удерживает DB-транзакцию; актуальность проверяется при apply |
| Structured Memory и bounded context | Факты имеют источник; embeddings и семантический поиск не обязательны для текущего этапа |
| Отдельные shipping roots | Legacy не запускает скрытые routes/workers и не возвращает Redis-зависимость новому пути |
| Import marker до запуска | Переход выполняется только после сверки перенесённого TutorStore |

## Изоляция legacy и CMP

Старый FastAPI runtime находится в [`app.legacy.main`](ai-service/app/legacy/main.py).
Путь `app.main:create_app` сохранён для совместимости. Старый Ktor wiring находится в
[`legacy/LegacyApplication.kt`](cmp/server/src/main/kotlin/com/eliteteam/speakingcoach/legacy/LegacyApplication.kt).
Текущие точки запуска не монтируют calls, clips, Vocabulary Builder, reminders, dashboards и app API.
Старые HTTP adapters использует test harness за пределами fat JAR.
Старые PG adapters остаются вне текущего runtime.

Переносится только текущий TutorStore. Legacy Redis namespaces не мигрируют в рамках SP-00.
Серверные Redis/PG данные и применённые Flyway-миграции сохраняются.
Порядок freeze/export/import/reconciliation описан один раз в [runbook](ai_docs/integrations/operations.md#sp-00-новый-runtime-и-перенос-текущего-чата).

CMP Android/iOS/Desktop остаётся отдельным прототипом. Его app API не монтируется новым runtime.
Устройство клиента, WebRTC и прежний HTTP-контракт собраны в [справочнике CMP](ai_docs/integrations/mobile-api.md).
Шаг и пример Review для Ktor и CMP определены в [core](cmp/core/src/commonMain/kotlin/com/eliteteam/speakingcoach/data/ReviewStepDto.kt).
Оболочки Review сохраняют настройки чтения и записи клиента и сервера.
Справочники voice/practice/analytics описывают сохранённый код и не являются перечнем активных маршрутов SP-00.

## Карта исходников

Пути Kotlin ниже относительны к `cmp/server/src/main/kotlin/com/eliteteam/speakingcoach/`.

| Задача | Файлы |
| --- | --- |
| Запуск, маршруты и доступ | `SpeakyApplication.kt`, `foundation/FoundationRoutes.kt`, `FoundationIdentity.kt`; `ai-service/app/speaky_main.py`, `foundation_api.py` |
| Сохранённый legacy runtime | `legacy/LegacyApplication.kt`; `ai-service/app/legacy/` (старый FastAPI, Clip pipeline и голосовой STT/TTS) |
| Бизнес-операции и данные | `foundation/FoundationService.kt`, `FoundationDatabase.kt`, `LessonManagement.kt`, `LearningState.kt` |
| Сохранение входа и восстановление хода | `foundation/TelegramTextIntake.kt`, `ConversationRuns.kt`, `FoundationChat.kt`; `ai/TutorClient.kt` |
| Диалог и native tools | `ai-service/app/foundation_tutor.py`, `tutor.py`, `foundation_client.py` |
| Общая память и контекст | `foundation/StructuredMemory.kt`, `ConversationHistory.kt`, `TutorContextOperations.kt`; `ai-service/app/context_builder.py` |
| Фоновые вычисления | `foundation/FoundationJobs.kt`, `FoundationWorker.kt`, `BackgroundContracts.kt`; `ai-service/app/learning_engine.py`, `lesson_voice.py` |
| Импорт, очистка и восстановление | `foundation/TutorStateImport.kt`, `FoundationImportCli.kt`, `FoundationMaintenance.kt`, `FoundationMaintenanceCli.kt`; `ai-service/scripts/export_tutor_state.py` |
| Голос и frontend | `foundation/TrialAccess.kt`, `TrialCommands.kt`; `ai-service/app/trial_api.py`, `realtime_voice/`, `trial_audio.py`; `mini-app/src/` |
| Roadmap, память и прогресс | `foundation/RoadmapPreparation.kt`, `MemoryOperations.kt`, `LessonMemory.kt`, `LearningProgress.kt`; `ai-service/app/roadmap.py`, `memory_context.py`, `memory_tools.py` |
| Подписка и расписание | `foundation/SubscriptionBilling.kt`, `TrialSchedule.kt`, `TrialWorker.kt`; `ai-service/app/subscription_tools.py`, `trial_tools.py`, `scheduling_tools.py` |
| Прототип и поставка | `cmp/app/`, `infra/`, `.github/workflows/` |

[Исходная архитектура MVP v1](ai_docs/architecture/speaky-mvp-high-level-architecture-v1.md) задаёт целевые обязанности будущего MVP.
Она не описывает текущий runtime. [План SP-00](ai_docs/plans/2026-11-10-sp00-foundation.md) фиксирует объём задачи,
[статус](ai_docs/status.md) — доказательства проверок и ограничения.
Архитектурные изменения следует отражать здесь, не создавать параллельную system map.

## SP-02: пробный голосовой урок

Самостоятельный `mini-app/src` собирается в nginx frontend; AI не раздаёт UI.
`app.js` выбирает экран. `call.js` владеет микрофоном, PCM и WebSocket.
`schedule.js` показывает встречи и экспортирует календарь. `progress.js` показывает учебный маршрут.
Выбор встречи передаёт ID в точку сборки, где начинается звонок.
`trial_api.py`
проверяет подписанное Telegram initData через Ktor `TrialAccess`. Backend владеет Lesson,
Call Session, scope и admission. Browser не получает provider key или право выполнить tool.

`RealtimeTrialSession` в `ai-service/app/realtime_voice/session.py` соединяет Web Audio с OpenAI Realtime
(`gpt-realtime-2.1`, Marin) через серверный WebSocket relay. Relay архивирует оба PCM-потока
в private S3 и подтверждает metadata в PostgreSQL **до** пересылки. Direct browser→provider
не выбран: серверный архив через sideband не подтверждён, а browser upload может потеряться
при закрытии WebView. Дополнительные S3/DB операции и RTT требуют живого замера.

Сессия хранит состояние звонка и исполняет решения по порядку. `connection.py` обслуживает WebSocket
и проверяет, можно ли отвечать. `responses.py` выбирает ответ и возвращает сессии необходимые изменения
состояния. `lesson_flow.py` определяет переходы урока, время и завершение без доступа к сокетам.
`tools.py` исполняет native tools через сессию и Foundation. `audio.py` буферизует PCM,
архивирует звук и ограничивает отчёты воспроизведения.

`semantic_vad` использует `eagerness=medium`. Speech-start останавливает playback; сервер
согласует `conversation.item.truncate` с ограниченным playback report. Отчёт клиента не
доказывает, что ученик услышал звук. Входной ASR transcript служит истории и анализу,
не генерации ответа. Модель получает `finish_after_reply` и memory tools SP-07.

`TrialCommands` сохраняет события, завершает урок и ставит evaluation/notice. `TrialPlanner`
даёт briefing, `TrialAnalyzer` анализирует разговор, `TrialWorker` обслуживает follow-up.
Формат голосовых команд задан в [контракте v1](contracts/voice/v1/commands.json).
[Карта задач после урока](ai_docs/integrations/lesson-results.md) показывает расчёт Python и применение в Ktor.
Foundation CI проверяет запросы Python и ответы Ktor на PostgreSQL.
V39 добавляет voice lease/scope, audio metadata и demo receipt. Аудио удаляется через 30 дней;
backup и S3 lifecycle остаются отдельными гарантиями. Reconnect открывает новый provider session
в том же Lesson. Ограничение одного транспорта — 50 минут; оно не означает завершение Lesson.
Старые `TrialSession`/STT/TTS не создаются shipping root.

Контракт ученика и живая матрица приёмки — [SP-02](ai_docs/plans/2026-11-11-sp02-trial-lesson.md).
Конфигурация, архив и диагностика — [operations](ai_docs/integrations/operations.md#sp-02-mini-app-и-пробный-голосовой-урок).

## Регулярные уроки и расписание SP-03/SP-04

`TrialSchedule.schedule.agree` сохраняет согласованную серию и материализует executable regular
Lesson на 14-дневном горизонте. `TrialWorker` продлевает горизонт, отправляет уведомления
и фиксирует no-show. Перенос/отмена оставляют occurrence exception, чтобы worker не воссоздал
встречу. `LessonManagement` исполняет команды под `lockStudent`, с версиями и receipts.
V40 хранит DST issues и версии уведомлений. Старые placeholders не активируются автоматически;
явный перенос выбранного placeholder может сделать именно его executable.

`LessonPlanner` готовит regular objective/topic/strategy из общего контекста. Первый admission
требует актуального плана и завершённых прошлых анализов; он сохраняет execution_snapshot.
Reconnect использует этот снимок и исходное время конца. После 50 минут provider session
меняется в безопасной паузе; бесшовность требует живой проверки. `lesson.next` читает только
сохранённую будущую встречу. Завершение regular не создаёт trial follow-up.

Детальные операции, время, DST и проверка доставки — в [SP-03](ai_docs/plans/2026-11-12-sp03-regular-lessons.md)
и [SP-04](ai_docs/plans/2026-11-12-sp04-scheduling.md).

## Подписка SP-05

`SubscriptionBilling` владеет subscription, receipt и entitlement в PostgreSQL. V41 добавляет
checkout terms и billing events. FoundationChat отправляет карточку из backend-условий;
LLM не подтверждает оплату. Настроенный opt-in mock обрабатывает Telegram callback через
`MockBillingTelegram` и `MockPaymentAdapter`. `TrialAccess` проверяет entitlement при первом
regular admission и сохраняет его для reconnect. Trial остаётся бесплатным.
Реального provider нет. Договорённости, идемпотентность и границы mock — в
[SP-05](ai_docs/plans/2026-11-12-sp05-subscription.md); конфигурация — в [operations](ai_docs/integrations/operations.md#sp-05-тестовая-подписка).

## Roadmap и подготовка SP-06

`roadmap.prepare` использует FoundationJobs/LearningEngine. `RoadmapPlanner` вычисляет вне
транзакции, `RoadmapPreparation` проверяет источники и версии перед применением.
ContextBuilder передаёт одну актуальную проекцию text/voice/learning; существующий SP-03 planner
сохраняет roadmap revision в плане. Первый admission фиксирует execution_snapshot, а новая цель,
исправление памяти и перенос отзывают будущую подготовку. V42 сохраняет результат review в job.

Ошибки анализа, roadmap и плана доступны admission/Mini App и `lesson.list`. Завершённые до SP-06
анализы не получают roadmap job автоматически при deploy. Команды диагностики и retry — в
[operations](ai_docs/integrations/operations.md#sp-06sp-08-jobs-память-и-прогресс).
Правила активации и адаптации — в [SP-06](ai_docs/plans/2026-11-12-sp06-roadmap.md).

## Память репетитора SP-07

`MemoryOperations` в PostgreSQL владеет scoped facts, candidates и disputed observations.
V43 добавляет ограничения упоминания и сроки актуальности. Native memory tools общие для
Telegram и Realtime; голос использует источник только после durable append транскрипта.
`TrialAnalyzer` анализирует доступные части trial/regular; `LessonMemory` проверяет источники
и версии. `MemorySelector` выбирает bounded контекст; Core и запреты сохраняются вне выборки.

Forget очищает производные копии и отзывает epoch. Voice закрывает provider session,
а незавершённый урок возвращается на подготовку без удалённого execution_snapshot.
Четыре уровня памяти и пользовательские правила — в [SP-07](ai_docs/plans/2026-11-12-sp07-memory.md).
Лимиты, ошибки и recovery — в [operations](ai_docs/integrations/operations.md#sp-06sp-08-jobs-память-и-прогресс).

## Прогресс SP-08

Общий `TrialAnalyzer` добавляет evidence metadata к observations. `RoadmapPlanner` оценивает
качественную динамику в том же `roadmap.prepare`; `LearningProgress` сверяет актуальные
источники вне relevance-выборки. V44 хранит metadata и versioned `progress_review`.
Смена transcript/review revision отзывает подтверждение, forget удаляет производные результаты.

Одна backend-проекция доступна ContextBuilder и Mini App `action=progress` после Telegram
initData auth. Просмотр не запускает Lesson, entitlement или jobs. Telegram My Progress открывает
`MINI_APP_URL` с `?view=progress`. Критерии навыка, ограничения Fluency и UX — в
[SP-08](ai_docs/plans/2026-11-12-sp08-progress.md).

Подтверждение trial: `TutorBookingDelivery` отправляет HTML-виджет Telegram с версией состояния и текстовой подписью;
V46 отличает старые текстовые сообщения. `TrialReminders` использует пояс/версию урока и durable receipts.
Экспорт календаря проходит Telegram auth/ownership в `TrialAccess`, затем `trial_api`/`lesson_calendar.py`;
он не даёт доступ к звонку или аккаунту календаря. Контракты доставки и ограничения — в [tutor-chat](ai_docs/integrations/tutor-chat.md).

## Учебные каталоги: SP-02 v5.5

Источник — `learning-catalog/v1/catalog.json`: шесть Skills, девять Problem types, 163 целевых Topics A1–C1.
Topic содержит стабильный ID, Skill, CEFR, название, ожидаемую способность, границы и источники.
Prerequisites и обязательного Curriculum Order нет. Зависимости и персональный порядок относятся к будущей Roadmap Generation.

`LearningCatalogModel` строго проверяет schemaVersion 2, ID, оси и источники.
Offline `LearningCatalogCli` валидирует без БД, импортирует черновик или отдельно публикует принятую версию.
`LearningCatalog` хранит неизменяемые версии, digest, записи и current pointer в PostgreSQL (V48/V49).
Импорт не публикует каталог; публикация сохраняет reviewer и ссылку на приёмку.
После публикации новая версия может уточнять названия и метаданные ревью, но повторное использование ID не может менять
смысл Skill/Problem или Skill, CEFR, objective, scope и coverageArea Topic. Такое изменение требует нового ID;
удаление опубликованного ID тоже отклоняется. Старые версии остаются доступны для уже сохранённых ссылок.
Неопубликованные черновики можно исправлять в новой версии.

`GET /internal/foundation/v1/catalog` требует service token, без student scope; читает только опубликованные версии.
Фильтры: `version`, `skill`, `cefr`, `topicId`. Ответ schemaVersion 2 не содержит зависимостей.
Python читает через `FoundationClient.catalog()`, без отдельной изменяемой копии.
Каталог не создаёт состояния учеников, Learning Tasks или Roadmap.
[Обзор содержания](ai_docs/product/2026-11-15-learning-catalog-review.md), [поставка и проверки](ai_docs/status.md).

## Student Learning State: SP-03 v5.5

`StudentLearningState` в Ktor — владелец учебного профиля; `StudentTopicKnowledge` — оценок Topics и их истории.
V50 добавляет `student_learning_state` и `student_topic_knowledge`. Цель остаётся в единственной таблице
`learning_goal`, но её запись больше не принадлежит `StructuredMemory`. Переноса старых учеников нет.

- Текущий CEFR: `currentLevels.selfAssessment` и `currentLevels.speaky`, каждый содержит `overall` и `skills`.
  Оценка состоит из `level` (A1/A2/B1/B2/C1/Unknown) и `explanation`. Общий уровень не вычисляется из Skills.
- `targetLevel` — единственный общий целевой CEFR. Практическая цель и nullable-дедлайн хранятся отдельно.
- `intensity`: nullable `lessonsPerWeek` и `lessonDurationMinutes`; положительные числа, без диапазонов.
- `homework`: `consent` yes/no/unknown, nullable неотрицательное `minutes` и `period` day/week/null.
  Положительное количество минут требует периода; ноль допускает неизвестный период.
  Отказ не меняет цель или оценку знаний.
- Topic Knowledge: Known / Not Known / Presumed Not Known / Unknown, объяснение, источники, компонент и время.
  Каждая запись добавляет ревизию; текущая оценка — последняя. Нет числового mastery и механизма оспаривания.
  Отказ изучать Topic не меняет знания; выбор задач относится к будущей Roadmap.

Внутренний контракт `/internal/foundation/v1` требует service token и student execution scope:

| Операция | Контракт |
| --- | --- |
| `GET /learning-state` | Профиль, revision, единственная goal и provenance изменённых полей |
| `GET /learning-state/topics` | Обязательная catalogVersion; optional topicId, offset, history. Страница до 50 записей; history требует topicId |
| command `learning.update` | expectedRevision, patch, optional goal, component, sources; отсутствующее поле сохраняется, null очищает nullable-поле |
| command `learning.topic.record` | catalogVersion, topicId, expectedRevision, status, explanation, component, sources |
| command `learning.forget` | expectedRevision; явное удаление учебного состояния и его производных копий с epoch/deletion ledger |

Общие записи доступны только operator scope, через student lock, operationId и receipts.
SP-05 добавляет узкую команду `onboarding.update` для text scope: goal, overall selfAssessment,
targetLevel и deadline. `OnboardingLearning` проверяет текущий вход, цитату для каждого изменения,
разрешённые поля и revision; backend формирует provenance и вызывает общий writer SP-03.
Native tool `update_onboarding` не получает operator scope и не может менять системный CEFR,
Topic Knowledge, интенсивность или Homework. Voice/learning scopes по-прежнему только читают.
`memory.record`, personal candidates и `tutor.update_context` отклоняют учебные поля.
Telegram schema и Memory tools теперь принимают только персональные факты; legacy TutorStore не меняется.

`Sources` — массив `{type: event|observation, id, revision}`. Для event revision означает sequence;
для observation — source_revision существующего durable Observation. Backend проверяет владельца,
существование, актуальную ревизию и доступность исходного текста при записи. Несуществующие Observations
не создаются автоматически. Полный новый producer Observations остаётся SP-17.
Known/Not Known требуют источники; Presumed Not Known сохраняет явное объяснение гипотезы.
Backend проверяет структуру и происхождение, а не педагогическую достаточность доказательства.

Истечение срока исходного текста сохраняет оценки, объяснения и ссылки. Чтение возвращает
`sourceContentExpired` и `sourceRevisionCurrent`; сырые цитаты в новом хранилище не копируются.
Обновлённый transcript не делает старую ссылку новым доказательством.
Персональное `memory.forget` очищает только связанные с источниками факта поля учебного профиля,
цели и ревизии Topics. Источники собираются из всех ревизий факта и кандидатов.
Единица зависимости — исходное сообщение: разные сведения из одного сообщения удаляются вместе.
Backend сохраняет `sourceEventId` и `lessonId` в ссылках, включая Observations; удаление Observation
не разрывает цепочку происхождения. Независимые поля и оценки сохраняются.
Связанная текущая оценка становится Unknown с новой ревизией; её прежние основания стираются.
У гипотез без источников очищается только свободное объяснение, статус сохраняется.
Удаление периода Homework очищает также минуты, чтобы сохранить валидный контракт.
Полный сброс учебного состояния выполняет явный `learning.forget`. Оба удаления отзывают epoch.
`/start`, delete и replay deletion ledger включают новые таблицы.

Каталог должен быть опубликован. Оценки закреплены за catalogVersion/topicId; новая публикация не переносит
их автоматически. Отсутствующая оценка в явно выбранной версии читается как Unknown.
Snapshot добавляет отдельные `learningState` и первую страницу `topicKnowledge` текущего каталога.
ContextBuilder передаёт их независимо от MemorySelector, сокращает объяснения/список по бюджету и отмечает
`contextPartial` с endpoint для полного чтения. Отсутствие темы в сокращённом контексте не означает Unknown.
Поле `goal` для существующих planners — проекция той же цели, без fallback на Memory.
Изменения отзывают будущую подготовку через learning_revision; начатый урок сохраняет execution snapshot.

Onboarding заполняет перечисленные пользовательские поля через native tool;
Post-Trial Assessment и Post-Lesson Review ещё не заполняют новую модель автоматически.
Новый UI, педагогические переходы и назначение Homework не входят в SP-03.
[Утверждённый план](ai_docs/plans/2026-11-15-sp03-student-learning-state.md), [проверки и поставка](ai_docs/status.md).


## Learning Tasks: SP-04 v5.5

`LearningTasks` в Ktor хранит персональную учебную работу отдельно от Topic Knowledge.
V51 добавляет `learning_task` и `learning_task_history`. Task связывает Student, ревизию существующей
цели SP-03 и совместимые Skill/Topic/Problem опубликованной версии SP-02. Каталоговая версия
закрепляется при создании; новая публикация не создаёт новую Task для тех же стабильных ID.

Новая Task всегда Identified/0. Поддерживаются переходы Identified → In Progress → Mastered → Reopened → In Progress.
Смена состояния не обнуляет Plan/Progress. SP-04 принимает внешний Progress 0–100 и decisionId,
но не вычисляет Stage Score, пороги или педагогическую достаточность Mastered.
Progress=100 не завершает Task автоматически; Task Mastered не меняет Topic Knowledge.

`details` содержит nullable целочисленный priority, nullable plan reference и массивы actions/homework references.
Ссылки имеют `{id, revision?}`; это непрозрачные идентификаторы будущих доменов, без их контента и статусов.
SP-12A проверяет существование Plan, принадлежность той же Task/ученику и явную текущую revision;
реальная проверка существования Action/Homework добавляется владельцами этих доменов при интеграции;
сами ссылки не предоставляют доступ к объектам другого ученика.
Значение приоритета задаёт внешний компонент; SP-04 не сортирует маршрут педагогически.

| Внутренний API | Назначение |
| --- | --- |
| `GET /learning-tasks` | Фильтры topicId/problemId/lifecycle, offset, до 50 записей |
| `GET /learning-tasks/{id}` | Текущее состояние Task в student scope |
| `GET /learning-tasks/{id}/history` | История по revision DESC, offset, до 50 записей |
| `learning.task.create` | Goal/catalog references, component, optional reason/sources; `{created, task}` |
| `learning.task.update` | taskId, expectedRevision, component, patch и/или goalRevision |
| `learning.task.transition` | lifecycle, decisionId, optional готовый progress |
| `learning.task.progress` | Готовый progress и decisionId; не запускает расчёт |

Ответы имеют schemaVersion=1; Task использует имена колонок snake_case, команды — camelCase.
Чтение требует service token и student scope. Запись пока только operator; job/text/voice scopes не получают
новых прав. FoundationClient предоставляет адаптеры, native tools их не открывают.
Общий student lock, expectedRevision, operationId и receipts защищают атомарную запись состояния и истории.
Повтор создания возвращает существующую Task, включая Mastered, без автоматического Reopened и без изменения цели.
Частичный UNIQUE index запрещает две активные Task с одинаковыми Student+Topic+Problem.
Поиск по другой Problem создаёт независимую Task. Смена цели сама по себе не меняет учебную работу.

История хранит previous_state/state, component, decisionId, reason и проверенные event/observation sources.
Источники не содержат копии транскрипта; чтение отмечает истечение срока и актуальность исходной ревизии.
Personal forget очищает свободные reason во всей истории ученика, где факт мог быть пересказан,
но сохраняет ID, lifecycle, Progress, Plan и историю структурированных изменений.
`learning.forget`, delete, новый `/start` и deletion replay удаляют Tasks и их историю.
Доставка Roadmap, генерация Tasks и педагогический выбор Plan, вычисление прогресса и включение в урок остаются следующими SP.

## Task Learning Plan: SP-12A v5.5

`LearningPlans`/`LearningPlanModel` в Ktor хранят структуру плана конкретной SP-04 Task.
V54 добавляет `learning_plan`, неизменяемые `learning_plan_revision` и историю выбора текущего этапа.
Plan не заменяет существующие `lesson_plan`, `LessonPlanner` или Trial phases.

Один Stage на выбранный Teaching Method: Conversation, Explanation, Exercises, Exposure.
Numeric weights неотрицательны, сумма точно 1 при наличии numeric stages; binary condition
`understanding_demonstrated` не имеет веса. В Plan входят только необходимые методы (`required=true`).
Состав по Problem выбирает Python `PedagogicalPlanner` SP-12B по шести утверждённым базовым
шаблонам Skill+Problem и опубликованному каталогу. Он создаёт Plan через SP-12A, возвращает
`template_missing` без записи и даёт правило планового перехода по готовому результату SP-19.
Веса заданы в шаблонах; LLM не выбирает их. Отказ не сдвигает cursor. SP-12B не считает Score
или Progress и не создаёт материал SP-13. Вызов из живого урока ожидает интеграции SP-16/19.

StageRef — `taskId + planId + planRevision + stageId`. Исторический resolver проверяет владельца,
Task и membership в указанной revision, включая удалённые из текущей версии этапы.
Reorder/reweight сохраняет Stage ID; смена method/kind/condition требует нового ID.
Cursor имеет отдельную stateRevision; переход/возврат не сбрасывает Progress и не создаёт попытку SP-19.

Create/revise атомарно обновляют Plan и Task.details.plan под student lock/receipts/expected revisions.
Запись только operator, чтение в student scope через internal API и FoundationClient.
Plan не содержит личного текста/материалов. Полный reset/delete/learning.forget/deletion replay удаляет
его вместе с Task по FK cascade; personal forget и retention сохраняют учебную структуру.
Подробный [контракт API и handoff](ai_docs/integrations/learning-plan.md), [проверки](ai_docs/status.md).

## Learning Content Contract: SP-13A v5.5

`LearningContentModel` проверяет единый документ для Explanation, Exercises, Exposure и
Conversation: Task/Topic/Problem, исторический StageRef, метод, формат действия, короткое
голосовое условие и описание допустимых ответов. Это описание задания, не автоматическая
оценка ответа ученика. Реальную генерацию/семантическую проверку выполняет будущий SP-13B;
AI во время SP-14/15 решает по фактическому ответу, SP-17C пишет Observation, SP-19 считает Score.

[Допустимые actionKind](ai-service/app/learning-content-action-kinds-v1.json) используют генератор,
голосовой обработчик и валидатор Ktor. Gradle кладёт тот же файл в артефакт Ktor.

V55 хранит `learning_content` и неизменяемые документы `learning_content_revision`.
`contentId + revision` показывает ровно то условие, которое было выдано ученику; исправление
создаёт новую revision. Статусы revision — prepared/issued/defective. Выдача требует
`validation.status=passed` от trusted operator, текущий Task/Plan/Stage и regular Lesson того
же ученика. SP-13A проверяет форму и ссылки, но не заявляет, что проверка языкового качества
уже реализована. Старый Content читается по прежней Plan revision после изменения Plan;
новая подготовка не использует устаревший StageRef. Content не меняет Plan, Score или Progress.

Команды create/revise/issue/mark-defective и GET current/revision/history доступны через
internal foundation API и Python FoundationClient; запись пока только operator. Personal forget
удаляет Content ученика, сохраняя Task/Plan. Полный learning reset, новый `/start` и delete
удаляют его каскадом от Task. Контракт и границы — в
[handoff](ai_docs/integrations/learning-content.md); готовность и проверки — в [status](ai_docs/status.md).

## Telegram Onboarding: SP-05 v5.5

Активные `text_tutor_policy.py`, `TrialTutorModel` и `FoundationTutor` ведут знакомство:
практическая цель, текущая самооценка, целевой CEFR A1–C1/Unknown, трудности и необязательный дедлайн.
Краткие описания помогают выбрать уровень; предложенная моделью метка становится самооценкой
только после согласия ученика. Unknown с описанием допустим. Трудности сохраняются как
пользовательский факт `difficulty` существующей Personal Memory, не диагностическая Observation.
Отдельная история обучения не собирается; частота/длительность и Homework обсуждаются на Trial (SP-06).
Прямой запрос Trial выполняется без заполнения профиля. После будущей записи репетитор завершает
разговор; дальнейшая беседа начинается по инициативе ученика. Отказы по уровню/цели исключены,
гарантии результата не даются. На базе SP-05 Trial оставался 15 минут; локальный SP-06 v5.5 ниже переводит новые звонки на 30 минут.
Контекст передаётся через существующий snapshot/ContextBuilder, без новой модели ученика или Tasks.


## Trial Lesson: SP-06 v5.5

`FoundationTutor` создаёт Trial на 30 минут; `LessonManagement` отмечает его `trial_version=v5.5`.
V52 добавляет phase/clock fields и отдельный raw `trial_call_event`, без Observation или Learning Plan.
`TrialExecution` суммирует подключённое время, хранит фазу и ограничивает reconnect исходным слотом.
Вернувшийся вовремя ученик использует оставшиеся минуты после конца слота. End button завершает Lesson;
отключённый Lesson после конца слота закрывает worker. Regular сохраняет прежние правила времени.

`trial_v55.py` задаёт RU warm-up → EN conversation → RU wrap-up; Realtime получает мягкие cues,
native phase/preferences tools и общий контекст. `TrialPreferences` проверяет active voice scope,
user source, evidence, revision и allowlist нагрузки, затем пишет через SP-03.
CEFR, Topic Knowledge, Homework assignment и расписание этим tools недоступны.

`TrialCallEvents` хранит ссылки на conversation_event, техническую identity/revision и transport metadata.
`TrialEventRoutes` отдаёт scoped paginated raw events и реальный audio manifest для SP-17B.
ASR confidence остаётся null; детектор ASR ошибок отсутствует. Transcript correction сохраняет source identity.
Retention и privacy cleanup включают новую таблицу. Архивирование до пересылки сохраняется.

Новый Trial использует memory-only extraction; backend запрещает старые educational observations/notes,
а старый RoadmapPreparation исключает Trial v5.5. Первичный CEFR формирует SP-07 после
Observations; Roadmap и Telegram delivery ожидают SP-08/11A.
Контракт, ограничения и обязательный список последующего удаления legacy — [Trial v5.5](ai_docs/integrations/trial-v55.md).

## Observation Contract & Core: SP-17A v5.5

`LearningObservations` в Ktor — журнал доказательных наблюдений; он не вызывает модель и не рассчитывает Score.
V53 добавляет `learning_observation`, `learning_observation_revision` и `learning_observation_source`.
Стабильная identity отделена от версии интерпретации. Student lock, receipts и DB uniqueness защищают повторы;
исправление сохраняет исходное происхождение и заменяет текущую интерпретацию, а не создаёт вторую попытку.

`LearningObservationModel` проверяет классификацию, исход, самостоятельность и структуру качества.
`LearningObservationSources` проверяет реальные conversation sources, их Student/Lesson/revision/span и доступность.
Unknown Topic/Problem остаются null. Каталожные ссылки требуют опубликованной версии и совместимого Skill.
Исходная ошибка и помощь сохраняются раздельно. ASR detector и автоматическая классификация в core отсутствуют.

Внутренние read/history endpoints и record/revise/invalidate commands доступны через `FoundationClient`.
Запись только operator; native tools не добавлены. `ObservationStageResolver` позволяет подключить historical
Plan resolver SP-12A; локальная интеграция подключает его по точной исторической ревизии.
Action refs остаются unresolved до их собственного домена; binary Stage не допускается к numeric scoring.
JSON-примеры и Python wire types закрепляют handoff для SP-17B/C, SP-07 и SP-18/19.

SP-03 принимает новый source type `learning_observation` с observation revision и всеми исходными event IDs.
Старые `event`/`observation` сохраняют семантику. Personal forget учитывает источники всех ревизий;
связанные observations удаляются, свободные пояснения остальных очищаются. Full learning reset/delete/replay
удаляют журнал. Retention показывает недоступность источников, но сам не меняет оценки знаний.

Прежний `memory_observation`/post-call pipeline сохранён. Автоматического backfill или второго классификатора нет.
Реальный Trial producer подключён в SP-17B; Teaching Actions и regular Topic Knowledge inference — SP-17C;
Score — SP-18/19, применение регулярных выводов — SP-20. Запись evidence не меняет эти состояния.
[Точный контракт, примеры и ограничения интеграции](ai_docs/integrations/learning-observations.md).

## Trial → Observations: SP-17B v5.5

Завершение Trial ставит отдельный `trial.observe` job рядом с memory-only `lesson.evaluate`.
`TrialObservationExtractor` читает сохранённые raw events через `FoundationClient`, берёт финальные
реплики и опубликованный каталог. Результат включает Speaking, Grammar, Vocabulary и ограниченные Listening Observations без Topic/Problem.
Ktor применяет весь результат в транзакции через `LearningObservations` с проверкой источников,
ownership и текущей transcript revision. Источник Observation — `sourceEventId/sourceSequence`;
raw `eventId/revision` остаются транспортной identity. Повтор job не создаёт вторую запись;
изменение интерпретации сохраняет history, исчезнувшие свидетельства invalidated.
Trial собирает Listening по связи устной реплики, ответа и фактической помощи. После успешного Observation batch
`trial.assess` формирует предварительный CEFR для наблюдавшихся Skills и Topic Knowledge;
системный Listening остаётся Unknown. Подробный [SP-07 handoff](ai_docs/integrations/trial-assessment.md).
