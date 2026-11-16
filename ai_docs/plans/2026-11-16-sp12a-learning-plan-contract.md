# SP-12A — Learning Plan Contract

Дата: 2026-11-16. **Пользователь утвердил после интервью: «Отлично, супер, мне нравится погнали реализовывать план».**
Контракт реализации: [Learning Plan](../integrations/learning-plan.md); результаты проверки — в [status](../status.md).

База: `origin/bb/sp-02-05-v5-5`, `7e3800bdac533360b69bcd250fd958bb798e2b7e`.
Отдельная ветка: `bb/sp-12a-learning-plan-contract-thr_kdrsx7xmdj`.
Родитель: `thr_3viaq32qtq`; SP-17A: `thr_3yyacry6cr`; SP-06: `thr_tvbpxhm5dn`.

## 1. Цель и границы

Сохранить проверяемый персональный Plan конкретной Task: методы, этапы, веса,
обязательность, переходы и текущий этап. Следующие SP получают один контракт.
Task может существовать без Plan. Привязка Plan не меняет Skill, Topic, Problem,
Goal, lifecycle или Progress. Plan сохраняется между уроками.

Входит: модель, валидация, PostgreSQL, внутренние операции и чтение, Python adapter,
история версий, Stage resolver, concurrency/idempotency, ownership и privacy.

Не входит: педагогический выбор методов/весов (SP-12B), content (SP-13),
исполнение Actions/урока (SP-14–16), Observations producer (SP-17),
Score/Progress/пороги/переоткрытие попыток (SP-19), Review (SP-20), новый UI.
Нет новых native tools, автоматического подключения Plan к голосовому runtime,
изменений Notion, commit/push/PR/deploy или интеграции соседних веток.

## 2. Источники и сверка

- [SP-12A](https://app.notion.com/p/41a045c3d57681159b63c302e8df3649), все 5 AC.
- [Родительская SP-12](https://app.notion.com/p/418045c3d57681a7a720cea1581f07ca), все 10 разделов.
- [Implementation Backlog](https://app.notion.com/p/418045c3d57681e0a077f8213d2816c0).
- [Workflow & Ownership](https://app.notion.com/p/419045c3d576810db38ac9d7cd7d6d45).
- v5.5: актуальное вложение Speaky_Personalized_Learning_v5_5.md со страницы
  [Speaky](https://app.notion.com/p/412045c3d57680fd99b8f5e49553603c), получено через Notion MCP;
  локальная [продуктовая спецификация](../product/2026-11-14-personalized-learning-v5.5.md).
- Дочерние SP-17A, SP-13A, SP-19A прочитаны через Notion MCP.
- [SP-04](2026-11-16-sp04-learning-tasks.md), [status](../status.md), [architecture](../../architecture.md).

| Проверенная база | Следствие |
| --- | --- |
| SP-02: опубликованные версии каталога и стабильные ID | Использовать каталоговую связь Task, не копировать/менять классификацию |
| SP-04: learning_task, history, details.plan={id,revision?} | Добавить реальную проверку ссылки; сохранить nullable Plan |
| LearningTasks.change пока проверяет только форму plan reference | Закрыть обход валидации через generic task.update |
| FoundationService: lock, scope, receipts, expected revisions | Переиспользовать общую транзакцию |
| regular_lesson.LessonPlanner выдаёт objective/topic/strategy | Это план занятия, не Task Plan; не менять существующий runtime |
| Новый Plan/Stage domain отсутствует | Создать отдельные файлы, не переименовывать старый LessonPlan |

Сверка исходников не означает повторную проверку тестов, DEV readiness или живую приёмку.
GBrain недоступен: lock другого gbrain serve. Notion и код доступны.

## 3. Предлагаемая модель

`Plan`: schemaVersion, id, taskId, неизменяемая definition revision, stages,
порядок этапов, допустимые переходы; технические timestamps/component.
Один актуальный Plan reference у Task. Предлагается один Plan ID на Task с последовательными ревизиями.
Вся версия публикуется атомарно после проверки; пустой черновик не подменяет отсутствие Plan.

`Stage`: устойчивый id, method, assessment kind (numeric/binary), required,
numeric weight либо binary condition. Ровно четыре значения method:
Conversation, Explanation, Exercises, Exposure. Method не является UI-режимом.
Описание binary condition задаёт ожидаемое понимание; факт выполнения не хранится здесь.
Педагогическая достаточность свидетельств и результат принадлежат будущим SP.

Валидация:

- Непустой набор stages, уникальные ID, известные методы и виды оценивания.
  По решению пользователя: не более одного Stage каждого метода в одной версии Plan (максимум четыре).
  Разные Actions и повторные прохождения не создают дополнительные Stage того же метода.
- Numeric: конечный неотрицательный вес; сумма всех numeric weights равна 1, если numeric stages присутствуют.
  Использовать десятичную арифметику; не исправлять вход скрытой нормализацией.
- Binary: без numeric weight/Score; обязательность не добавляет процентов.
- Порядок содержит каждый Stage ID ровно один раз; ссылки переходов принадлежат этой версии.
- Циклы и обратные переходы допустимы. Порядок не означает запрет вернуться.
- Не создавать отсутствующие методы ради полноты, не вводить Homework requirement.
- Ненужные методы отсутствуют в Plan; не добавлять optional numeric Stage с отдельным весом.
- Состав методов определяется Problem и педагогическими правилами SP-12B, не произвольной комбинацией.
  SP-12A проверяет структуру, но не создаёт новый педагогический сценарий «только Explanation»
  и не определяет Progress/Mastered для него. Условная проверка суммы следует SP-12 §5.5.

## 4. Stage identity и история

Общая ссылка: `{taskId, planId, planRevision, stageId}`.
Student определяется проверенным scope; actionId находится в контексте потребителя.
Соседний SP-17A предварительно согласовал этот формат 2026-11-16.

- stageId не равен индексу или названию метода.
- Перестановка или изменение веса создаёт Plan revision, сохраняя Stage ID.
- Изменение method, numeric/binary kind или смысла binary condition создаёт новый Stage ID.
- Старый Stage остаётся доступен в прежней версии после удаления/замены в новой.
- Resolver проверяет student/task ownership и membership именно запрошенной planRevision.
- Исторические Observations/Content никогда не перенаправляются на текущую версию автоматически.
- Изменение Plan не переносит и не пересчитывает Score. Правила переноса — будущая SP-19/20.
- `stageAttemptId` зарезервирован отдельно для SP-19, nullable; не равен attemptId учебного ответа.
  SP-12A не создаёт попытки прохождения и не реализует их lifecycle.
- Полное удаление учебного состояния удаляет историю; historical resolution не обходит privacy.

Текущий этап хранится отдельно: `{planRevision, currentStageId|null, stateRevision}`.
Null означает отсутствие выбранного этапа, а не Mastered. Перемещение курсора не создаёт
новую definition revision. Повтор/возврат не стирает результаты и не переоткрывает попытку.
При замене версии требуется явно выбрать допустимый cursor или сохранить тот же ID, если он присутствует.

## 5. Переходы и команды

Предлагаемый граф содержит from/to Stage IDs и тип основания, без формул Score.
SP-12A проверяет структуру и применяет внешнее решение; SP-12B/19 выбирают следующий этап.
Явный выбор ученика допускает отдельное основание перехода между этапами текущего Plan.
Смена темы вне Task обрабатывается Lesson runtime; Plan не заставляет сначала пройти упражнение.
Переход курсора не означает, что предыдущий binary/numeric этап выполнен.

| Операция (предлагаемые имена) | Назначение |
| --- | --- |
| learning.plan.create | Создать валидный Plan и атомарно связать с принадлежащей ученику Task |
| learning.plan.revise | Добавить immutable definition revision, обновить Task reference под expected revisions |
| learning.plan.select-stage | Переместить/снять текущий cursor с основанием и optimistic lock |
| GET /learning-plans/{id}?revision= | Текущая или историческая definition и контекст Task |
| GET /learning-plans/{id}/history | Пагинация ревизий/истории выбора текущего этапа |
| Stage reference resolver | Однозначно разрешить полную ссылку для SP-13/17/19 |

Имена/разбиение эндпоинтов инженер может уточнить без смены семантики.
Запись только operator, чтение service-token + student scope. component не предоставляет права.
Транзакция сохраняет Plan, Task reference, истории и receipt вместе.
expectedTaskRevision/expectedPlanRevision/stateRevision предотвращают потерю параллельных изменений.
Повтор operationId возвращает receipt; иной payload с тем же ID конфликтует.
Чужая Task, отсутствующая версия/Stage, неверный вес, stale revision отклоняются без частичной записи.
Task.update не может привязать чужой Plan или Plan другой Task; revision для новой ссылки должна быть явной.

## 6. Privacy и совместимость

- Не копировать профиль, personal facts, транскрипты или материалы урока в Plan.
- learning.forget, новый /start, student.delete и replay deletion ledger удаляют Plan и историю.
- Personal forget сохраняет структурированную учебную работу; очищает свободные пояснения,
  если там мог оказаться личный факт. Не удаляет Stage и не меняет Progress.
- Retention транскрипта не удаляет Plan и не доказывает выполнение binary condition.
- Не мигрировать старые lesson plans/roadmap JSON в новую модель автоматически.
- Перед созданием миграции запросить номер у родителя; существующие Flyway не переписывать.

## 7. Этапы после явного утверждения

1. Завершить интервью, внести решения и получить явное утверждение этого плана.
2. Подтвердить identity с SP-17A; зарезервировать миграцию у родителя.
3. Добавить model/validator и общие JSON examples, затем PostgreSQL storage/history.
4. Подключить foundation commands/read/resolver, атомарную Task reference и Python adapter.
5. Добавить cursor operations и privacy/reset/deletion replay.
6. Проверить unit, PostgreSQL и реальный Python → Ktor → PostgreSQL workflow.
7. Обновить architecture/README/status и передать родителю контракт/AC/ограничения.

Общие файлы: FoundationService/Routes, LearningTasks/Model, FoundationClient,
FoundationMaintenance/StudentLearningPrivacy при необходимости, architecture/README/status.
Новые доменные файлы и тесты отдельные. SP-06 подтвердил разделение Trial phases и Task Plan.
SP-06 планирует возможные точечные изменения FoundationService dispatch, FoundationClient
и privacy/reset для transport metadata; точные места сообщит перед реализацией.
SP-12A не меняет Trial/lesson.prepare. Общие участки сверяются перед интеграцией родителем.
Родителю сообщены общие файлы; интеграцию веток выполняет он.

## 8. Приёмка

| Проверка | Ожидаемый результат |
| --- | --- |
| Четыре метода, смешанный Grammar Plan | Explanation binary + Exercises .3 + Conversation .7; AC-1/2 |
| Numeric weights | .3+.7 принимается; отрицательные/NaN/неверная сумма отклоняются; AC-3 |
| SP-04 Task без Plan → attach → read/restart | Plan долговечен; Skill/Topic/Problem/lifecycle/Progress неизменны; AC-4 |
| Historical resolution | SP-13/17/19 видят тот же Stage по полной ссылке; AC-5 |
| Reorder/reweight/remove | Старые ссылки валидны; нет retargeting или Score transfer |
| Repeat/back/user-directed cursor | Не сбрасывает завершённые этапы и не пишет Score/Progress |
| Ownership/auth/concurrency/replay | Нет чужих данных, дублей, частичной записи или потерянного update |
| Reset/forget/retention | Полное удаление очищает историю; personal forget сохраняет структуру |
| Обход через task.update | Нельзя сохранить несуществующую или несовместимую Plan reference |

Unit проверяют инварианты. PostgreSQL проверяет atomicity, историю, FK/ownership, rollback и privacy.
Реальный HTTP workflow использует опубликованный синтетический SP-02 каталог, реальную Task SP-04,
Plan и исторический resolver. Fixtures потребителей проверяют форму, но не означают готовность SP-13/19.
Запуски после реализации: pytest из ai-service; из cmp с GRADLE_USER_HOME=$HOME/.gradle:
`./gradlew :server:test :server:buildFatJar detekt`; DB/HTTP suite по operations; `git diff --check`.
DEV/PROD и живая AI-педагогика отдельно, не входят в доказательство foundation-контракта.

## 9. Решения интервью и сверка спецификации

- Пользователь утвердил упрощение: один этап на выбранный метод в конкретном Plan.
  Разные упражнения являются Actions внутри этапа; повтор не создаёт второй Exercises Stage.
- Пользователь уточнил цепочку Skill → Topic → Problem → комбинация Teaching Methods.
  Plan реализует педагогический путь решения Problem, не произвольный набор методов.
- Повторно прочитанные SP-12 §5/6 и SP-12B подтверждают выбор состава по Skill+Topic+Problem:
  неизвестная Grammar → Explanation/Exercises/Conversation; известная с ошибками → Exercises/Conversation;
  Listening опирается на Exposure/понимание. Правила состава относятся к SP-12B.
- Важная граница источников: v5.5 §16.2.1 содержит матрицу Problem → Exercise Actions,
  а не исчерпывающую фиксированную матрицу всех Teaching Methods для каждой из девяти Problems.
  Это не основание импровизировать комбинации; полная конфигурация и допустимая адаптация — SP-12B.
- Вопрос «разрешить ли только Explanation» снят с интервью SP-12A: он был задан вне контекста Problem.
  Не вводить новый педагогический сценарий. Структурная проверка весов следует условию SP-12 §5.5;
  она не подтверждает педагогическую допустимость Plan и не определяет его завершение.
- Необязательный метод не включается в Plan (v5.5 §13.1). Ситуативный дополнительный разбор
  не становится отдельным требованием к завершению Task.

Формулы, пороги, педагогический выбор комбинаций и новый UI остаются вне SP-12A.
После этих уточнений пользователь явно утвердил реализацию целиком. Родитель выделил миграцию V54.

### Инженерные уточнения в утверждённом объёме

- Binary condition хранится структурированным кодом `understanding_demonstrated`, без свободного личного текста.
  SP-12A не хранит подтверждение выполнения. Новые виды условий требуют явного расширения контракта.
- `required=true` у каждого включённого этапа; ненужный метод отсутствует по решению интервью.
- Переходы хранят from/to; причина выбора cursor — `plan_transition` либо `student_request`.
- В Plan нет свободного rationale, personal facts или копии контента. Отдельная redaction-операция
  для Plan не нужна: personal forget сохраняет структуру; полный reset/delete каскадирует от Task.
- Task link требует существующую текущую Plan revision. Историческая revision доступна по StageRef/read,
  но generic Task patch не может незаметно откатить активную ссылку на старую версию.
