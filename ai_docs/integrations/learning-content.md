# SP-13A: Learning Content Contract v1

Этот документ описывает формат материала для одной Learning Task и одного исторического Stage.
SP-13A хранит версии и проверяет структуру/ссылки. Он не вызывает модель, не создаёт реальный
материал, не слушает ученика и не рассчитывает Progress. Готовность и проверки — в
[status](../status.md); продуктовая цель — в [v5.5 §16](../product/2026-11-14-personalized-learning-v5.5.md).

## Для чего нужен контракт

Например, Plan выбрал Exercises Stage для Grammar Task. Будущий генератор SP-13B подготовит
короткую фразу и понятное голосовое условие. SP-14 произнесёт его и проведёт упражнение.
AI-репетитор решит, подходит ли фактический ответ ученика. SP-17C зафиксирует доказательное
Observation; SP-19 позже рассчитает Stage Score. `answerGuide` описывает допустимые ответы
**данного задания**, но SP-13A не оценивает ученика.

Схема примеров: [content-contract/v1/examples.json](../../content-contract/v1/examples.json).
Python wire types: `ai-service/app/learning_content.py`. Kotlin structural validator:
`LearningContentModel`. Примеры синтетические; `pending` не означает качество реального контента.

## Document v1

Обязательные поля:

| Поле | Значение |
| --- | --- |
| `schemaVersion` | Число `1`. |
| `taskId` | Принадлежащая ученику Task. Student задаёт execution scope. |
| `stageRef` | `{taskId,planId,planRevision,stageId}`; только текущая revision при новой подготовке/выдаче. Старые документы читаются по исторической revision. |
| `catalogVersion`, `skillId`, `topicId`, `problemId` | Должны совпасть с Task. Контракт не выбирает и не переименовывает Topic/Problem. |
| `method` | Ровно Conversation, Explanation, Exercises или Exposure; должен совпасть со Stage. |
| `actionKind` | Конкретный формат внутри метода. Он не является отдельным Stage и не принимает решений. |
| `cefr` | A1–C1 или Unknown. Это вход сложности, не новый вывод об ученике. |
| `objective`, `spokenInstruction` | Краткая цель и условие, которое можно произнести без экрана. |
| `payload` | Разный для четырёх методов, описан ниже. |
| `answerGuide` | `criteria` (1–8), `alternatives` (0–8), `unacceptable` (0–8). Ориентир для AI; не string-match evaluator. |
| `validation` | `{status:pending|passed|rejected, component, reasons[]}`. `rejected` требует причину. |

`LearningContentModel` проверяет форму, размер (до 16 000 символов JSON), поля, типы и
допустимые Action kinds. Он **не доказывает** языковую корректность, соответствие CEFR или
качество примеров. Только trusted operator может записать `passed`; реальный semantic validator
появится в SP-13B. На `pending`/`rejected` материал нельзя выдать.

| Method | `actionKind` | Payload |
| --- | --- | --- |
| Explanation | direct, comparison, learner_example | `explanation`, 1–4 коротких `examples`. |
| Exercises | sentence_construction, sentence_transformation, sentence_correction, translation, word_recall, meaning_recognition, meaning_matching, listening_reconstruction, listening_comprehension, rapid_response, sentence_expansion, paraphrasing | `stimulus`, `targetOperation`. |
| Exposure | listening_reconstruction, listening_comprehension, dialogue | 1–8 коротких `segments`; `checks` из reconstruction/meaning. Listening template SP-12B остаётся Exposure Stage даже при восстановлении фразы. |
| Conversation | personalized, situational, topic_driven | `openingPrompt`, 1–6 `targetOpportunities`; нет одного эталонного ответа. |

Списки `answerGuide` описывают смысл и варианты конкретного условия. При Listening
`reconstruction` ученик восстанавливает услышанную короткую фразу; `meaning` отдельно
проверяет понимание. Числовой порог буквального совпадения не задан.

## Identity и жизненный цикл

- Новый независимый материал получает `contentId` и revision 1. `learning_content` хранит
  текущую revision; `learning_content_revision` хранит неизменяемый document каждой версии.
- Статусы revision: `prepared → issued → defective` либо `prepared →` новая revision.
  Новая revision может заменить формулировку (`rephrase`), упростить её (`simplify`),
  заменить ошибочное условие (`replace_defect`) или добавить результат проверки (`validate`).
- После выдачи текст прежней revision не меняется. Ответ остаётся связан с тем условием,
  которое ученик услышал. Историческая revision доступна, даже если Plan изменился между уроками.
- `issued` означает, что trusted producer заявил выдачу в принадлежащем ученику regular Lesson.
  SP-13A не подтверждает фактическое озвучивание или слышимость. Это не Observation.
  Повтор той же выдачи в тот же Lesson идемпотентен; другая попытка конфликтует.
- `defective` помечает уже выданную revision стандартной причиной, временем и component. SP-17C в будущем
  сопоставит её с реальным ответом и `defective_content`; сам Content не создаёт Observation.
- Отказ ученика от упражнения не является дефектом материала и не меняет Stage/Score/Progress.
- После изменения Plan новые create/revise/issue с прежним StageRef отклоняются как stale.
  Чтение исторического Content и отметка дефекта прежней выданной revision остаются доступны.

## API и права

Все пути имеют prefix `/internal/foundation/v1`, требуют service token и student execution scope.
Запись только `operator`; native tools и новые права voice/text/job не добавлены. Команды используют
общий student lock, operationId, receipts и транзакцию FoundationService.

| Операция | Назначение |
| --- | --- |
| `learning.content.create` | `{document,component}`; проверяет текущие Task/Plan/Stage и создаёт prepared revision 1. |
| `learning.content.revise` | `{contentId,expectedRevision,document,component,reason}`; новая immutable revision с теми же Task/Stage/Method/Action identity. |
| `learning.content.issue` | `{contentId,expectedRevision,lessonId}`; только `validation.status=passed`, текущий Plan и regular Lesson того же ученика. |
| `learning.content.mark-defective` | `{contentId,revision,reason,component}`; помечает выданную, в том числе историческую revision. |
| `GET /learning-content/{id}?revision=N` | Текущая или историческая версия, статус и фактический Lesson выдачи. |
| `GET /learning-content/{id}/history?offset=N` | До 50 версий, по убыванию, с nextOffset. |

Ответы имеют `schemaVersion=1`. Python `FoundationClient` предоставляет соответствующие методы.
Повтор того же operationId с тем же payload возвращает прежний receipt; другой payload конфликтует.
Чужие Task/Plan/Stage/Lesson и чужой Content недоступны. Неверная текущая revision даёт
`version_conflict`; смена Task/Stage/Method/Action при revise — `content_identity_changed`.

## Privacy и следующий handoff

Content может содержать личную тему разговора. Personal forget удаляет весь Content ученика,
сохраняя Task/Plan. `learning.forget`, новый `/start`, student.delete и deletion replay удаляют
его через Task FK cascade. Нет автоматического backfill из старых `lesson_plan`, хранения audio
bytes, внешних видео или копий транскрипта. Retention transcript не делает material validation.

SP-13B получает `document`/`validation` и должен выполнить реальную генерацию/проверку перед
`issue`. SP-14/15 получают выданный материал и оценивают фактический ответ AI-репетитором.
SP-17C должен сопоставить Action instance с точным `contentId + revision`, а после дефекта
не выдавать scoring evidence. SP-12B определяет Plan из своих шаблонов; Content не меняет
метод/веса и не выбирает Stage. SP-17B извлекает Trial Observations и Content не использует.
