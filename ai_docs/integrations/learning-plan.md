# Task Learning Plan — SP-12A contract v1

Владелец: Ktor `LearningPlans` / `LearningPlanModel`. Хранение: V54, PostgreSQL.
Это персональный Plan конкретной SP-04 Task, отдельный от `lesson_plan` и `LessonPlanner`.
Педагогический выбор состава принадлежит SP-12B; content — SP-13; Score и попытки прохождения — SP-19.

## Definition

```json
{
  "schemaVersion": 1,
  "stages": [
    {"id":"explain","method":"Explanation","kind":"binary","required":true,"condition":"understanding_demonstrated"},
    {"id":"exercise","method":"Exercises","kind":"numeric","required":true,"weight":0.3},
    {"id":"converse","method":"Conversation","kind":"numeric","required":true,"weight":0.7}
  ],
  "order": ["explain","exercise","converse"],
  "transitions": [
    {"from":"explain","to":"exercise"},
    {"from":"exercise","to":"converse"},
    {"from":"converse","to":"exercise"},
    {"from":"exercise","to":"explain"}
  ]
}
```

Это синтетический пример формы, не универсальный план для Grammar.
Допустимы ровно четыре method: Conversation, Explanation, Exercises, Exposure.
Набор непустой, один Stage на method, максимум четыре. Ненужный метод не включается.
Все включённые Stage имеют `required=true`. Score, completion и content здесь отсутствуют.
Numeric weight — JSON number, конечное неотрицательное десятичное число. Если numeric stages есть,
сумма точно равна 1; сервер не округляет/нормализует неверный ввод. Binary не содержит weight.
Единственный binary condition v1 — `understanding_demonstrated`: требование, не доказательство его выполнения.
Структурно binary-only definition допустима по SP-12 §5.5; это не педагогическая конфигурация
и не правило Progress/Mastered. Их SP-12A не создаёт.

Order содержит каждый ID ровно один раз. Edges ссылаются на существующие этапы, без дублей.
Обратные связи/циклы допустимы. Actions, повторные упражнения и новые попытки не создают второй Stage того же метода.
ID/component — технические значения `[A-Za-z0-9][A-Za-z0-9_.:-]{0,119}`, не поля личного текста.

## Identity и чтение

Общая ссылка потребителей:

```json
{"taskId":"...","planId":"...","planRevision":1,"stageId":"exercise"}
```

Student определяется scope. Stage ID не индекс и не имя method; он уникален внутри Plan revision.
Перестановка/веса создают новую definition revision, сохраняют ID прежнего этапа.
Изменение method/kind/condition требует нового ID. Запрет повторного использования ID с другим смыслом
действует и для удалённых из текущего Plan этапов. Каждая прошлая definition остаётся доступна.
Переход cursor не создаёт definition revision и не переносит Score.

`actionId` и nullable `stageAttemptId` находятся в контексте SP-13/17/19, не в definition.
`stageAttemptId` — будущее прохождение этапа SP-19; оно не равно ID ответа/попытки Observation.
SP-12A не выдаёт и не валидирует эти идентификаторы.

Все routes имеют prefix `/internal/foundation/v1`, требуют service token и student execution scope.

| GET | Результат |
| --- | --- |
| `/learning-plans/{id}` | Текущая definition |
| `/learning-plans/{id}?revision=N` | Историческая definition |
| `/learning-plans/{id}/history?offset=0` | История definition/cursor по stateRevision DESC; 50 записей и nextOffset |
| `/learning-plans/{id}/stages/{stageId}?taskId=...&revision=N` | Исторический resolver: `{schemaVersion,reference,stage,task}` |

Read envelope: `{schemaVersion:1,plan:{id,taskId,revision,definition,component,createdAt,currentState}}`.
`currentState:{planRevision,stateRevision,currentStageId}` всегда описывает **текущую** версию,
даже если прочитана старая definition. Старая ссылка не перенаправляется на неё.
Resolver проверяет ownership Plan/Task, их связь и membership Stage именно в указанной revision.
`task` в resolver — текущая Task; использовать возвращённую `reference` для исторической привязки.
Удалённый из новой версии этап разрешается в старой версии. Полное удаление данных отменяет эту возможность.

## Запись

Обычный foundation command envelope v1, operationId и args; только role=operator.
Нет native tools и новых прав text/voice/job. Python `FoundationClient` предоставляет адаптеры.

| Action | Args |
| --- | --- |
| `learning.plan.create` | taskId, expectedTaskRevision, definition, component; optional currentStageId (default null) |
| `learning.plan.revise` | taskId, planId, expectedTaskRevision, expectedPlanRevision, expectedStateRevision, definition, component; optional currentStageId |
| `learning.plan.select-stage` | planId, expectedPlanRevision, expectedStateRevision, currentStageId (nullable, required), reason, component |

Create создаёт Plan revision=1/stateRevision=1 и атомарно записывает Task.details.plan={id,revision}.
На Task допускается один Plan ID. Повтор нового create для Task с Plan возвращает plan_already_exists;
повтор того же operationId возвращает исходный receipt. Task может существовать без Plan.

Revise добавляет revision, увеличивает stateRevision и атомарно меняет Task link/history.
Пропущенный currentStageId сохраняет прежний ID, explicit null снимает выбор.
Если прежний ID удаляется, вызов должен явно выбрать другой ID/null; иначе вся операция отклоняется.
Даже одинаковая definition при новом operationId сохраняется как явная новая ревизия.

Select меняет только cursor/stateRevision. reason=`plan_transition` проверяет edge при смене выбранного этапа;
reason=`student_request` позволяет выбрать любой существующий этап. Первый выбор, повтор текущего
или снятие выбора не требуют edge. Повтор текущего значения не добавляет историю.
Null означает «этап не выбран», не завершение Task. Ни выбор, ни возврат не выполняют binary condition,
не сбрасывают результаты и не открывают новую попытку SP-19.

Один student lock и транзакция защищают Plan, Task link, обе истории и receipt.
Task.update принимает только существующий Plan той же Task и ученика, с явной текущей revision.
Null по-прежнему снимает Task link; Plan/history сохраняются и могут быть снова привязаны.
Plan changes не меняют Skill/Topic/Problem, goal, lifecycle, Progress или Topic Knowledge.

Основные ошибки: `version_conflict`, `operation_conflict`, `plan_already_exists` (HTTP 409),
`not_owned`/`not_permitted` (403), `plan_task_mismatch`, `plan_revision_not_found`,
`stage_not_in_revision`, `stage_identity_changed`, `duplicate_stage_method`,
`invalid_plan_weight_sum`, `stage_transition_not_allowed` (400).
Неизвестные поля/query и неполные ссылки отклоняются.

## Privacy и handoff

Definition/history не копируют личный текст, профиль, rationale, материал или транскрипт.
Personal forget и retention сохраняют структуру Plan. learning.forget, student.delete,
новый /start и deletion replay удаляют Plan/revisions/history каскадом от Task.
Receipts и scope epochs очищает общий foundation privacy path.

- SP-12B передаёт выбранную конфигурацию через create/revise; никакой педагогики в storage validator.
- SP-13A использует StageRef и method; конкретный материал хранит самостоятельно.
- SP-17A хранит StageRef без автоматического retargeting; runtime-интеграция resolver после объединения веток.
- SP-19A получает kind/weight/required/condition и определяет своё состояние Score/attempts.
  Изменение Plan само по себе не переносит результаты между revision.
- SP-06 не зависит от этого domain; Trial phases и существующий lesson_plan сохраняют свой контракт.

Незавершённые соседние runtime не импортируются. Реальные AI/Voice/Score интеграции проверяются в следующих SP.
Локальные результаты и ограничения поставки — [status](../status.md).

## SP-12B: педагогический выбор и переходы

`ai-service/app/pedagogical_plan.py` выбирает один из шести утверждённых базовых шаблонов
для реальной Task и опубликованной версии каталога. Перед созданием проверяются Skill/Topic/Problem,
objective/scope Topic и принадлежность Problem тому же Skill. Личный текст, самооценка уровня
и неизвестный CEFR не превращаются в новый Problem или метод. Шаблон отсутствует — результат
`template_missing`, Task остаётся без Plan. LLM не задаёт состав или веса.

| Problem | Stages |
| --- | --- |
| `grammar_unknown` | Explanation binary → Exercises 0.3 → Conversation 0.7 |
| `grammar_application` | Exercises 0.3 → Conversation 0.7 |
| `speaking_barrier` | Conversation 1.0 |
| `listening_comprehension` | Exposure 1.0 |
| `vocabulary_passive` | Exercises 1.0 |
| `vocabulary_active` | Exercises 0.3 → Conversation 0.7 |

Каждый Stage ID — имя метода в нижнем регистре. Только соседние этапы имеют прямой и обратный edge.
`ensure_plan(scope, taskId, operationId)` читает Task и закреплённую в ней версию каталога,
создаёт Plan с expected Task revision либо возвращает уже связанный Plan без новой записи.
Повтор того же create с одним operationId обрабатывает receipt SP-12A; новый operationId
для уже связанной Task читает существующий Plan. Конфликт revision требует нового чтения и решения.

`stage_reference(plan, stageId)` выдаёт StageRef для SP-13A и будущего SP-19.
`advise_stage` принимает **готовое** доказанное условие Explanation либо numeric Stage Score 0–100.
Binary requirement → следующий этап; numeric `S≥85` → следующий этап; `S≤15` → сигнал пересмотра
с допустимым предыдущим этапом, но возврат не автоматический. Промежуточный Score сохраняет cursor.
`apply_stage_result` использует этот результат и фиксированный snapshot Plan; только плановый переход
вызывает `select-stage` с expected plan/state revisions. При retry вызывающий повторяет тот же
snapshot и operationId. Отказ оставляет cursor без изменения и возвращает `pause_task`;
беседа может продолжиться вне Task. Просьба ученика не вызывает выбор другого Stage.

SP-12B не выставляет Score, Progress, completion или новую попытку. Смена состава/весов возможна
лишь между уроками при новом утверждённом шаблоне; в текущей версии альтернативных шаблонов
и автоматической ревизии нет. Контекстные варианты и педагогическая проверка вынесены в
[будущую задачу](https://app.notion.com/p/41a045c3d5768171a550e644146167a2),
переходы по просьбе ученика — в [отдельную задачу](https://app.notion.com/p/41a045c3d576816abb4ff4f84b0405eb).
Listening Trial не оценивается и не создаёт входного Listening Observation для SP-12B.
