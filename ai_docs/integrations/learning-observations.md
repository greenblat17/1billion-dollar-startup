# SP-17A: Observation Contract v1

Это контракт хранения evidence, не extractor и не Score calculator.
Готовность и проверки — в [status](../status.md); scope — в [плане](../plans/2026-11-16-sp17a-observation-contract.md).
Python wire types: `ai-service/app/learning_observations.py`. Kotlin validation: `LearningObservationModel`.
Исполнимые [JSON-примеры](../../observation-contract/v1/examples.json) проверяются Kotlin validator и Python adapter test.

## API

Все endpoints используют `/internal/foundation/v1`, service token и student execution scope.
Чтение доступно существующим scoped consumers. Operator записывает и исправляет Observations;
SP-17C разрешает voice scope только запись regular evidence по выданному Action того же урока.
Новых native tools нет; Trial producer подключён в SP-17B, regular producer остаётся SP-17C.

| Операция | Аргументы |
| --- | --- |
| GET `/learning-observations` | optional lessonId/taskId/skillId/topicId/problemId; offset; до 50 записей |
| GET `/learning-observations/{id}` | текущая версия, включая invalidated |
| GET `/learning-observations/{id}/history` | offset; версии по убыванию, `isCurrent` |
| command `learning.observation.record` | `{observation}` |
| command `learning.observation.revise` | `{observationId,expectedRevision,observation,reason}` |
| command `learning.observation.invalidate` | `{observationId,expectedRevision,component,reason}` |

Команды используют существующие version=1/operationId/receipts и student lock.
Ответы schemaVersion=1. Запись возвращает `{created,observation}`.
Row использует snake_case; `document` и входные DTO — camelCase, как SP-04.
Исправление передаёт полный document, не patch. Invalidated нельзя воскресить.
История — audit, а не дополнительные попытки для расчёта.

## Минимальный пример

```json
{
  "schemaVersion": 1,
  "attemptId": "application-1",
  "evidenceSlot": "grammar-application-1",
  "anchorEventId": "persisted-user-event",
  "lessonId": "owned-lesson",
  "occurredAt": "2026-11-16T10:00:00Z",
  "sources": [{"eventId":"persisted-user-event","revision":3,"role":"user","purpose":"answer","span":{"start":0,"end":9}}],
  "catalogVersion": "published-version",
  "skillId": "grammar",
  "topicId": null,
  "problemId": null,
  "practice": "trial",
  "method": "conversation",
  "kind": "demonstration",
  "outcome": "independent_correct",
  "support": "independent",
  "initialOutcome": "correct",
  "quality": {"status":"usable","signals":[],"confidence":null},
  "provenance": {"component":"trial-observation-extractor","analyzerVersion":"v1"},
  "taskId": null,
  "stageRef": null,
  "actionId": null,
  "repeatOf": null,
  "route": "assessment_only",
  "rationale": "Самостоятельное применение в ответе ученика"
}
```

ID примера условные; backend требует реальные принадлежащие ученику источники и опубликованный каталог.
Skill обязателен. Topic/Problem могут быть null независимо друг от друга; неизвестные строки ID отклоняются.
Trial/regular требуют lessonId и соответствующую kind урока. Homework допускает источник вне Lesson.
Методы: conversation/explanation/exercises/exposure. Источник практики не создаёт пятый Teaching Method.

## Значение evidence

| Случай | outcome / initialOutcome / support |
| --- | --- |
| Самостоятельный успех | independent_correct / correct / independent |
| Ошибка без исправления | uncorrected_error / error / independent или unknown |
| Самостоятельное исправление | self_corrected_error / error / independent |
| Ошибка, затем помощь и правильный ответ | hinted_correct / error / hinted; sources содержит hint |
| Помощь до первого ответа | hinted_correct / unknown / hinted |
| Недостаточно оснований | unresolved / unknown / unknown |
| Невалидный эпизод | invalid; quality=invalid с явным основанием |

`kind=self_report|feedback` требует unresolved/invalid: сообщение «я сделал» не превращается в демонстрацию навыка.
Refusal и технический сбой не являются доказанной педагогической ошибкой.
`initialOutcome` сохраняет исходную ошибку при hinted correct. SP-17A не выбирает её вес.

Quality: usable/uncertain/invalid. Для uncertain/invalid обязателен `signals` с reason, component и sourceEventId.
Причины: uncertain_asr, ambiguous_interpretation, defective_content, missing_context, unknown_support, refusal, technical_failure.
Все сигналы ссылаются на источник данного Observation. Usable не допускает такие блокирующие сигналы.
Confidence — optional `{value:0..1,component,kind:interpretation|asr}`; порогов нет.
Null confidence не означает invalid. SP-17A не обнаруживает ошибки ASR и не повторяет транскрипцию.

`evidenceUsable` требует действующей версии, доступных актуальных источников, usable quality,
демонстрации, определённой самостоятельности, outcome не unresolved/invalid и отсутствия repeatOf.
Это проверка структуры и доступности, не гарантия педагогической истинности.
`scoringEligible` дополнительно требует единственного маршрута problem_detection или проверенного numeric stage_progress.
Никакие Score, CEFR, Topic Knowledge или Task lifecycle при записи не меняются.

## Identity и no double-counting

- Producer присваивает attemptId осмысленному применению; evidenceSlot — устойчивому аспекту конкретного source anchor.
- DB UNIQUE(student,anchorEventId,evidenceSlot) не позволяет сделать повтор новым событием сменой operationId/attemptId.
- Для определённых Topic/Problem действует отдельный UNIQUE(student,attemptId,topicId,problemId) среди active observations.
- Новые независимые применения в одной реплике получают собственные anchors/spans/attempts. Producer отвечает за смысловую независимость.
- `repeatOf` ссылается на observation того же ученика; цепочки повторов запрещены. Повтор хранится, но не учитывается как новое доказательство.
- Повтор record с тем же содержанием возвращает существующую запись. Изменённое содержимое требует revise; иначе observation_identity_conflict.
- Revise сохраняет anchorEventId/attemptId/evidenceSlot/lessonId/occurredAt/practice/repeatOf; source revisions, classification и интерпретация могут уточняться.
- Analyzer version не входит в identity. Переанализ сопоставляет старые anchors, а не генерирует новые попытки.
- Актуальная версия заменяет прежнюю. Consumer должен хранить observation ID/revision и заменять прежнее влияние при revise/invalidate.
  Реальный пересчёт — SP-18/19; consumer не может складывать все строки history.
- Semantic repetition detection не реализован SQL-индексом. Его качество проверяется на реальных данных в SP-17B/C.

Маршруты: assessment_only, problem_detection, stage_progress, unresolved.
Problem detection требует известные Topic/Problem и отсутствие активной Task этой проблемы на момент записи.
Stage progress требует taskId и полный stageRef. Одна запись не может иметь два маршрута.
Поздняя смена интерпретации/маршрута — новая revision; старое влияние не должно оставаться параллельно с новым.

## Источники и handoff SP-06

V1 sources ссылаются на `conversation_event`: eventId=id, revision=sequence.
Backend проверяет Student, роль, sequence, доступность текста и принадлежность Lesson при указанном lessonId.
Source purpose: answer/context/hint/correction. Anchor обязан быть answer ученика.
Указанный span — Unicode code point offsets `[start,end)`, без копии текста. Конец не выходит за длину источника.
Provenance содержит component и optional analyzerVersion/model/promptVersion; неизменная история сохраняет прежние источники.

Согласованный SP-06 raw envelope имеет **другие** eventId/revision:

| SP-06 transport | SP-17A source |
| --- | --- |
| sourceEventId | eventId |
| sourceSequence | revision |
| raw eventId/raw revision | не подставлять вместо conversation source reference |
| callSessionId/providerItemId/turnId/finality | контекст SP-17B extractor |
| metadata.role | role |
| actual audio refs/playback/explicit uncertainty | дополнительные основания интерпретации; не выдумывать |

SP-06 endpoints: `/trial/events?lessonId&offset`, `/trial/audio?lessonId&cursor`.
Provider audio offsets и archived PCM offsets имеют разные basis; автоматическое выравнивание не предполагается.
SP-06 producer contract находится в его поставке `ai_docs/integrations/trial-v55.md`.
SP-17B подключает их после завершения Trial через `trial.observe` и связывает ошибку,
подсказку и самокоррекцию по сохранённым репликам. Extractor принимает только финальные
transcript sources, выбирает точную цитату и Unicode span, проверяет каталог и отдаёт
Speaking/Grammar/Vocabulary с `route=assessment_only`. Ktor применяет batch через
`LearningObservations` под job fence и student lock; повтор безопасен, устаревший результат
не применяется. `GET /trial/events` показывает отдельный `observationStatus`.
Listening не оценивается на Trial; отсутствие Observation не означает низкий уровень.
В SP-17A нет зависимости от V52 и реального Trial extractor.

## Исторический Plan/Stage

`taskId` отдельно; stageRef = `{planId,planRevision,stageId,stageAttemptId?}`. actionId nullable отдельно.
Task реально проверяется по Student/Skill/Topic/Problem. Plan/Action существование не объявляется проверенным без resolver.
`ObservationStageResolver` — integration port. По умолчанию возвращает unavailable, `referenceStatus=unresolved`, scoringEligible=false.
SP-12A предоставляет `LearningPlans.resolve(c,student,taskId,planId,revision,stageId)` с envelope `{schemaVersion,reference,stage,task}`.
Adapter извлекает stage, сохраняя проверки owner/task/revision/membership. Ссылка никогда не перенаправляется на current revision.
Методы SP-12A явно переводятся: Conversation→conversation, Explanation→explanation, Exercises→exercises, Exposure→exposure.
Stage kind numeric/binary проверяется; binary не становится numeric scoring event.
SP-17C проверяет actionId по исторической выданной версии Content; без совпадения referenceStatus остаётся unresolved.
stageAttemptId принадлежит SP-19, не создаётся в A. Исторические ссылки не переносят Score между Plans.

## SP-17C: Teaching Actions и Topic handoff

`teaching_observations.observation_from_action` переводит результаты SP-14/15 в тот же v1 document.
Сохранённый ответ даёт `anchorEventId`, `attemptId`, время и `conversation_event.sequence`.
Повторная запись использует устойчивый operation ID. Realtime hook записывает Observation после
сохранения расшифровки и до следующего ответа; ошибка записи прерывает обработку и не скрывается.
`actionId` содержит `contentId:revision`. Foundation проверяет, что именно эта историческая
версия Content выдана для того же ученика, урока, Task, Stage и метода. Дефектный Content
сохраняет evidence с `referenceStatus=defective`, но его ссылка не считается verified для Score.
Voice scope может записать только regular Observation по выданному Content этого же урока;
для дефектной версии допускается только invalid outcome. Revise/invalidate остаются operator-only.

SP-15 Listening и Conversation дают `independent_correct` только для показанного результата;
подсказка требует сохранённый hint source, сомнительное ASR остаётся unresolved. Один и тот же
текст ответа в рамках действия не создаёт второе Observation. SP-14/15 различают явную
self-correction и hinted answer. Это структурная проверка, не доказательство качества живого распознавания.

`topic_knowledge_advisory` читает историю Observations по Topic и отдаёт SP-20 ссылки на
ревизии и источники, `decision=insufficient_evidence`, `value=null` и rationale. Утверждённых
критериев достаточности Known/Not Known пока нет. Поэтому этот интерфейс не меняет Topic Knowledge;
SP-20 должен сохранить прежнее значение до отдельного продуктового решения.

## SP-03, retention и privacy

Новый source type: `{type:"learning_observation",id,revision}`.
Старый `observation` сохраняет прежнюю семантику source_revision, без переименования или backfill.
Новая ссылка проверяет актуальную revision, active status и доступность всех источников.
В lineage сохраняются anchor и все sourceEventIds; удаление Observation не должно разорвать privacy цепочку SP-03.

Истечение raw retention не удаляет сохранённые оценки SP-03, но источники становятся unavailable для новой интерпретации.
Личные forget удаляют связанные Observations по источникам **всех** ревизий и очищают свободный rationale/reason у остальных.
Существующий forget также очищает raw history: независимый Observation сохраняется структурно, но sourceContentExpired=true.
Learning forget, новый /start, student.delete и deletion replay удаляют весь журнал, ревизии и source links.
Никаких копий raw transcript или аудио в новом хранилище нет.

## Коды отказов и граница проверки

Основные коды: observation_identity_conflict, observation_identity_immutable, duplicate_problem_evidence,
version_conflict, observation_invalidated, observation_source_not_owned, stale_observation_source,
source_expired, invalid_source_span, invalid_observation_catalog, observation_task_mismatch,
observation_active_task, hint_source_required, stage_reference_required, not_owned, not_permitted.

Fixtures проверяют типы, историю, no double-counting и unresolved boundaries.
PostgreSQL/HTTP проверяют реальные storage/ownership/receipts. Они не доказывают качество ASR, tagging,
real Trial→Observation, Teaching Actions или пересчёт Score. Эти интеграции остаются следующей волне.
