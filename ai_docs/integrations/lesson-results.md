# Результаты урока и следующие задачи

Ktor ставит задачи, выдаёт `target_id`, `input_refs` и `fence`. Python рассчитывает результат через
[LearningEngine](../../ai-service/app/learning_engine.py). Ktor проверяет и сохраняет его в одной
транзакции с завершением задачи. Review выводит итог из уже сохранённых данных в Ktor.
Проверки полей принадлежат принимающим обработчикам. Эта карта указывает на их контракты.

## После пробного и регулярного урока

| Задача | Вход и обязательные ссылки | Результат, проверка и переход |
| --- | --- | --- |
| `lesson.evaluate` | Lesson, `transcriptRevision`, `goalRevision`, `analyzerVersion`, `memoryOnly`. Факты, кандидаты, Observations и notes ссылаются на сохранённые реплики ученика. | Обязательны `status` (`evaluated` или `insufficient_evidence`) и `observations`. Факты, кандидаты и notes передаются отдельно. Trial v5.5 допускает только личную память и пустые учебные результаты. Проверки возвращают `invalid_result`, `invalid_evidence`, `source_expired` или `legacy_trial_assessment_forbidden`. Ktor хранит evaluation по Lesson, ревизии и версии анализатора. Regular повторно ставит Review с новым digest. [Схемы Python](../../ai-service/app/learning_results.py) · [Приём в Ktor](../../cmp/server/src/main/kotlin/com/eliteteam/speakingcoach/foundation/FoundationJobs.kt) |
| `trial.observe` | Завершённый Trial v5.5, `transcriptRevision`, `analyzerVersion`, raw transcript и опубликованный каталог. Ссылка сохранённого источника содержит event ID, sequence, роль и назначение. | Обязательны `schemaVersion: 1` и `observations`. Пустой batch допустим. `initialMaterializationOnly` допускается для непустого результата до первой записи. Ktor проверяет Lesson, владельца и ревизии источников. Ошибки включают `invalid_result`, `stale_observation_source`, `observation_source_not_owned` и `source_expired`. Совпадающий документ сохраняет ID и revision. Изменение создаёт revision, исчезнувший источник инвалидирует учебную запись. Успех ставит `trial.assess`. [Контракт Observation](learning-observations.md) |
| `trial.assess` | Lesson, `transcriptRevision`, `observationJobId` успешного batch и `catalogVersion`. Ссылается на текущие ID и revisions Observations. | Обязательны `schemaVersion`, `catalogVersion`, `baseLearningRevision`, `observationRefs`, `levels`, `topics`, `coverage`. Ошибки включают `invalid_assessment_source`, `invalid_assessment_coverage` и `unsupported_skill_level`. Устаревший набор ссылок получает `superseded`. При слабой опоре сохраняется Unknown. Listening остаётся Unknown. Roadmap ставится при сохранённых цели и целевом CEFR. [Контракт Assessment](trial-assessment.md) |
| `lesson.review` | Завершённый Regular, `transcriptRevision`, `evidenceDigest` от текущих revisions Observations и evaluation. | Worker передаёт `{}`. Ktor выводит Review v1, сохраняет версию и историю, возвращает `superseded` при смене digest. Дополнительное поле даёт `unknown_field`. Ktor может перевести Task в Mastered только при пригодных Observations и реальном расчёте Stage Score. При наличии Roadmap ставит `roadmap.prioritize`. [Контракт Review](post-lesson-review.md) |

## Задачи, которые используют эти результаты

| Задача | Вход и обязательные ссылки | Результат, проверка и переход |
| --- | --- | --- |
| `roadmap.generate` | Lesson, `transcriptRevision`, `learningRevision`, `goalRevision`, `catalogVersion`, `knowledgeDigest`. | Документ содержит `schemaVersion`, `catalogVersion`, `goalRevision`, `targetLevel`, `goal`, `provisional`, `sequence`, `taskCount`, `assumptions`, `history`, `scope` и `timeline` (может быть null). Элементы sequence ссылаются на Skill, Topic и Problem. Ktor связывает записи с Tasks, проверяет каталог и ревизии. Ошибки включают `invalid_roadmap`, `invalid_roadmap_topic`, `stale_roadmap_task`. Смена входа даёт `superseded`. Сохранение ставит первоначальный `roadmap.prioritize`. [Контракт Roadmap](../plans/2026-11-17-sp08-roadmap-handoff.md) |
| `roadmap.prioritize` | `roadmapRevision`. После Review также Lesson, `reviewRevision` и `trigger`. | `expectedRevision` и `decision` с `schemaVersion`, `roadmapRevision`, `trigger`, `orderedTasks`, `nextTaskId`, `focusTaskIds`, `deferredTaskIds`, `declinedProblems`, `needsRoadmapReview`, `explanation` и `scope`. При отсутствии подходящей Task следующий ID пуст и требуется пересмотр Roadmap. Ktor проверяет решение, membership, зависимости и текущие revisions. Ошибки включают `invalid_priority_decision`, `stale_priority_task` и `version_conflict`. [Проверки Ktor](../../cmp/server/src/main/kotlin/com/eliteteam/speakingcoach/foundation/RoadmapPriority.kt) |
| `roadmap.prepare` | Старый маршрут для поддерживаемых уроков. `goalRevision`, `learningRevision`, `roadmapRevision`, актуальные legacy observations и их источники. | Обязательны `decision`, `reason`, `steps`, `sourceIds`. Возможны `progress` и `dimensions`. Ktor проверяет источники и передаёт оценки в LearningProgress. Для пересмотра существующего плана нужны новые свидетельства из двух уроков. Коды включают `invalid_result`, `invalid_evidence` и `insufficient_evidence`. Ветка `keep` сохраняет версию плана. [Подготовка Roadmap](../../cmp/server/src/main/kotlin/com/eliteteam/speakingcoach/foundation/RoadmapPreparation.kt) · [LearningProgress](../../cmp/server/src/main/kotlin/com/eliteteam/speakingcoach/foundation/LearningProgress.kt) |
| `lesson.prepare` | Следующий Lesson, `goalRevision`, `learningRevision`, `roadmapRevision`, `lessonVersion`. Учитывает актуальные evaluations и выбранную Task. | Обязательны `objective`, `topic`, `strategy`. Опциональный `teachingPlan` указывает на Task, её revision и priority revision. Задача ждёт анализа и готовности Roadmap. Ktor проверяет `invalid_result`, `roadmap_not_ready`, `analysis_not_ready`. Без текущего Stage Content не ставится. [Проверки плана и Content](../../cmp/server/src/main/kotlin/com/eliteteam/speakingcoach/foundation/FoundationJobs.kt) |
| `lesson.content.prepare` | Lesson и его version, Task, Plan и его revision, `planStateRevision`, Stage и `priorityRevision`. | Обязателен `document` Learning Content с подтверждённой validation и тем же StageRef. Несовпадение результата даёт `invalid_result`, устаревший вход даёт `superseded`. Повтор использует ранее подготовленный Content. [Контракт Learning Content](learning-content.md) |

Regular Teaching Action записывает Observation во время урока. Ktor обновляет Stage Score и Task Progress
по пригодному Observation. Review читает это состояние после звонка.
[Проверки Stage Score](learning-score.md).

Ktor выбирает доступные задачи в порядке `lesson.evaluate`, `lesson.review`, `trial.observe`,
`trial.assess`, `roadmap.generate`, `roadmap.prepare`, затем прочие по времени. Устаревший Review может
завершиться как `superseded`, когда evaluation меняет digest. Повтор с тем же operation ID и результатом
возвращает сохранённый receipt. Изменённый payload даёт `operation_conflict`.
Чужая задача или истёкшая аренда дают `not_owned` или `stale_job`. Worker повторяет
`provider_timeout` и `provider_error` в пределах числа попыток. `invalid_result`,
`handler_unavailable` и `budget_exhausted` завершают задачу. Истечение срока источников даёт `source_expired`.
[Очередь и статусы](../../cmp/server/src/main/kotlin/com/eliteteam/speakingcoach/foundation/FoundationJobs.kt).

HTTP-тесты используют Ktor и отдельную PostgreSQL. Они проверяют повтор записи, удалённое обязательное
поле и откат batch при неверной ревизии реплики.
[Trial Assessment](../../ai-service/integration/test_trial_assessment_http.py) ·
[Regular Evaluation и Review](../../ai-service/integration/test_lesson_replay_http.py).
