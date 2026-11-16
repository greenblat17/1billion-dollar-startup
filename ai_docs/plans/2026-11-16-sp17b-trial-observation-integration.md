# SP-17B — Trial Observation Integration

**Статус:** утверждён пользователем 2026-11-17; локальная реализация и проверки выполнены, поставка не выполнялась.
**База:** `origin/bb/sp-02-05-v5-5` at `2487b22`; отдельная ветка SP-17B.

## Цель и результат для ученика

После полного или прерванного пробного звонка Speaky сохраняет наблюдения из реально записанного разговора по Speaking, Grammar и Vocabulary. Listening — отдельный навык; Trial его не оценивает и не создаёт Listening Observations. Если по навыку данных нет, SP-17B не создаёт Observation; SP-07 может опереться на самооценку и известный контекст для осторожной гипотезы, сохраняя их отдельно от наблюдений. Listening current CEFR после Trial остаётся Unknown до отдельной проверки этого навыка. Во время Trial репетитор не объявляет CEFR, баллы или итоговый диагноз.

## Входы и границы

- SP-06 даёт scoped `GET /trial/events` и `GET /trial/audio`: raw event identity/revision, `sourceEventId/sourceSequence`, Lesson/Call Session, finality, playback и реальные audio refs. Raw event ID и source event ID различаются.
- SP-17A даёт Observation v1, журнал ревизий, проверку source ownership, identity и `record/revise/invalidate`. Его `sources[].revision` — `conversation_event.sequence`, не `trial_call_event.revision`.
- SP-02 даёт опубликованный каталог Skills/Topics/Problems. ID выбираются только из его версии; Unknown остаётся `null`.
- SP-03/04/12A предоставляют student/task/plan структуры, но Trial Observation использует `route=assessment_only`, без Task/Stage/Action.
- SP-07 позже принимает первичный диагностический вывод. SP-17B не присваивает CEFR, Topic Knowledge и не создаёт Roadmap.
- SP-17C позже подключает Teaching Actions и regular lesson. SP-17B не выбирает Plan/Teaching Method, не создаёт Content и не рассчитывает Score.

## Сценарии

1. Полный Trial: русские warm-up/wrap-up отделены от основной английской практики; реальные ответы могут дать Observations по Speaking, Grammar и Vocabulary. Listening в Trial не измеряется.
2. Тревожный ученик молчит, отвечает по-русски или прекращает звонок: нет ошибочного Observation за отсутствие ответа; доступны лишь фактические свидетельства.
3. Ученик отвечает по смыслу на реплику репетитора: этот контекст помогает понять его речь, но не становится Listening Observation или Listening CEFR.
4. Ученик ошибается, сам исправляется или отвечает после подсказки: фиксируется различный `outcome`, `initialOutcome`, `support` и соответствующие source refs.
5. Система использует сохранённую ASR-расшифровку. Если источник явно сообщает неопределённость, либо нужный контекст или assistant playback отсутствует, конкретное Observation получает `uncertain`/`unresolved` с причиной. Отдельной перепроверки аудио, второго ASR и ручного ожидания нет.
6. Повторная доставка того же raw event не создаёт второго Observation. Новая revision того же source приводит к пересмотру текущей записи; технический replay не считается новой попыткой.
7. Фраза после подсказки не становится независимым успехом: Observation отражает доступный контекст помощи. Отдельное событие после reconnect само по себе не считается ни дублем, ни независимым доказательством; используются фактические реплики и контекст.
8. Несколько навыков в одной реплике: отдельные обоснованные evidence slots допустимы; одна и та же ошибка по конкретной Problem не дублируется.
9. Нет опубликованного каталога, истёк source, сбой модели или валидации: job сообщает проверяемый отказ и не записывает выдуманный результат.

## Trial raw events → Observation v1

| Raw источник | Observation |
| --- | --- |
| `sourceEventId` / `sourceSequence` | `sources[].eventId` / `sources[].revision`; anchor — ответ ученика |
| raw `eventId` / `revision` | ключ дедупликации и проверки изменения транспорта; не подменяет source ref |
| `lessonId`, `occurredAt` | тот же Lesson и время фактического ответа |
| `callSessionId`, `providerItemId`, `turnId` | контекст связывания; не самостоятельный учебный attempt ID |
| `metadata.role`, `body`, `finality` | user answer, assistant context/hint, transcript span; partial не равен полному сообщению |
| `asrConfidence=null`, явные `uncertaintySignals` | confidence остаётся null; явный сигнал учитывается, а отсутствие confidence не блокирует анализ |
| playback metadata и audio refs | дополнительное основание; неизвестный или частичный playback не доказывает, что ученик услышал полную реплику |

Producer создаёт устойчивые `attemptId` и `evidenceSlot` по смысловой попытке и anchor. Все документы имеют `practice=trial`, `method=conversation` при разговорной демонстрации, `route=assessment_only`, `taskId/stageRef/actionId=null`. `skillId` обязателен. Topic/Problem могут быть null. `provenance` включает компонент, analyzer/model/prompt version, когда они известны. Не сохраняем копии transcript/audio в Observation.

Для этого producer допустимы только `skillId` Speaking, Grammar и Vocabulary. Listening не записывается даже при понятном ответе на вопрос: полноценная оценка принадлежит отдельному будущему сценарию.

`quality=usable` означает структурно пригодное свидетельство из сохранённой расшифровки, без дополнительной проверки аудио. Явная неопределённость получает `quality=uncertain` и ссылку на source. Само `asrConfidence=null` не делает ответы невалидными. Refusal, technical failure и self-report не становятся самостоятельным учебным успехом или ошибкой.

## Обработка, ревизии и доступность

1. После завершения Lesson backend ставит отдельный idempotent Trial observation job. Существующий `lesson.evaluate` остаётся Personal Memory extractor; его `memoryOnly` результат не считается диагностикой.
2. Worker в learning scope читает все страницы raw events и каталог, проверяет `schemaVersion`, Lesson ownership, finality, порядок и текущие source revisions. Основной материал анализа — сохранённые transcripts; архив аудио не перепроверяется.
3. Extractor получает bounded контекст разговора и возвращает typed кандидаты с точными source refs/spans, rationale и provenance. Нельзя использовать свободный текст модели как доказательство без проверки refs, ролей, каталога, hint и source freshness.
4. Backend применяет batch под student lock и job fence. Он повторно проверяет source revisions и Observation v1, затем пишет записи и завершает job в одной транзакции. Python не получает operator credentials. Если кандидат невалиден, batch не создаёт частичный диагностический набор.
5. Повтор job использует стабильные identities. Для изменённого source или новой версии extractor применяется `revise` текущего Observation; history сохраняется. Если Observation уже invalidated, его нельзя воскресить. Поздний stale result не перезаписывает новую ревизию.
6. SP-07 читает только текущие active версии и `evidenceUsable`, видит uncertain/unresolved отдельно. Отсутствие Observation не трактуется как ошибка или низкий уровень. SP-07 самостоятельно использует self-report и известный контекст как предварительную гипотезу. Listening current CEFR остаётся Unknown; self-report по Listening хранится отдельно.

Технический выбор job и batch должен сохранить существующую гарантию `learning.observation.record/revise/invalidate`; если для атомарного batch нужен новый внутренний endpoint, он использует тот же validator и writer, а не вторую модель. Результат job не содержит полного transcript.

## Изменения по слоям

- **Python:** новый Trial extractor и типы результата; `LearningEngine`/composition root подключают job; `FoundationClient` читает raw pages и Observation, передаёт результат. Не менять realtime transport без доказанной необходимости.
- **Ktor:** trigger при завершении Trial, job kind/apply, статус и stale guards; использовать `LearningObservations` для записи и `TrialCallEvents` для source handoff. Сохранить privacy reset/forget/retention и Personal Memory.
- **Документация:** точечно обновить `learning-observations.md`, `trial-v55.md`, `architecture.md`, `status.md`, карту в `ai_docs/README.md`.
- **Mini App / Telegram:** нет нового интерфейса или обещания готовой Roadmap в этом этапе. Полный пользовательский путь проверяется после SP-07/08/11A.

## Миграции и совместимость

Существующие V52 (raw events) и V53 (Observation journal) уже дают нужные таблицы; `foundation_job.kind` хранится как `TEXT` без SQL enum. Основной вариант не требует новой Flyway миграции. Если после проектирования выяснится нужда в новом durable processing marker или колонке, сначала запросить номер миграции у родителя, затем добавить только новую V-миграцию. Исторические V52–V54 не изменять. Никакого backfill старых Trial и переноса личных данных в рамках SP-17B.

## Проверки и приёмка

- Контрактные Python-тесты на реальные SP-06 envelopes, пагинацию, source mapping, Unicode spans, catalog Unknown, Speaking/Grammar/Vocabulary, отсутствие Listening Observation даже при смысловом ответе, hint/self-correction, technical replay, явный ASR signal/partial playback и short Trial.
- Kotlin/PostgreSQL-тесты на trigger, scope/fence, атомарность batch, replay, source revision changes, stale/invalidate и privacy cleanup.
- HTTP Python → Ktor → PostgreSQL: завершить синтетический Trial через действующий путь, прочитать сохранённые raw events, получить Observation v1, перечитать их для SP-07; повторить job и убедиться, что нет второго evidence.
- Регрессия Personal Memory, audio archive, reconnect и старого regular flow. Проверить `pytest`, `:server:test :server:buildFatJar detekt`, `git diff --check`.
- Отдельно указать, что синтетические интеграционные тесты не доказывают качество живой речи, ASR, Telegram, S3 или DEV/PROD. Живую приёмку провести только в разрешённой последующей поставке; после HTTP 402 не повторять платные прогоны.

## Риски и открытые решения

- Качество классификации Speaking/Grammar/Vocabulary на естественном разговоре проверяется на реальных примерах, а не следует из схемы.
- Ошибка в единственной ASR-расшифровке может дать ложное Observation. Система опирается на сохранённый transcript и учитывает только доступные явные сигналы неопределённости; это известное ограничение качества.
- Частичный assistant playback ограничивает контекст ответа, но не порождает Listening оценку.
- Последующая коррекция raw source требует безопасного revise/invalidate; потеря или истечение источника ограничивает переанализ.
- Короткий Trial может не дать Observation по Speaking, Grammar или Vocabulary. SP-07 вправе использовать самооценку и прежний контекст только как гипотезу; сохранение и показ первичной диагностики принадлежат SP-07. Listening не оценивается в Trial при любой его длительности.
- При интеграции общей ветки пересечения с SP-12B/13A ограничены `LearningEngine`/composition root и документацией. Plan и Content остаются их зонами. Родитель согласует merge и номера Flyway.

## Разрешение на реализацию

Пользователь разрешил реализацию: «погнали реализовывать». Commit, push, PR, merge и deploy требуют отдельного поручения.

## Результат локальной реализации

`trial.observe` ставится при завершении Trial v5.5 рядом с memory-only evaluation.
Python читает сохранённые raw events, выбирает только финальные реплики и опубликованный каталог,
строит typed Observations с точными source revisions и span. Kotlin применяет их через
SP-17A writer в одной транзакции; повтор не создаёт второй записи, отсутствие прежнего
свидетельства ведёт к invalidate. `observationStatus` доступен в Trial events API.
Listening, CEFR, Plan, Content и Roadmap не формируются.

Проверено локально: 710 Python unit, 321 Kotlin unit, 116 PostgreSQL integration,
1 HTTP Python → Ktor → PostgreSQL; Fat JAR, detekt, ссылки и `git diff --check`.
HTTP сценарий использует фиксированный результат модели. Качество вывода на живой речи,
Realtime/Telegram/S3 и DEV/PROD не проверены.
