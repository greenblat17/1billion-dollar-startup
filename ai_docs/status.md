# Состояние Speaky

Срез документации: 2026-11-17. Волны 1–9 отправлены в [PR #58](https://github.com/greenblat17/1billion-dollar-startup/pull/58) к `dev`; PR не слит. DEV получил Ktor из `2513429`, AI service из `974230e` и Mini App из `2f38856`; [проверка готовности](https://github.com/greenblat17/1billion-dollar-startup/actions/runs/44090164120) прошла. Живые текстовые сценарии и состояния PostgreSQL записаны в [отчёте по AC](acceptance/runs/text-ac-results.md). [Проверка Telegram](acceptance/runs/telegram-vlama-2026-11-17.md) подтвердила `/start`, прямую запись Trial и доставку кнопки «Начать урок». Mini App показал включённый микрофон и идущий таймер. Голосовой диалог ещё не проверен. Волны 10–11 исключены из текущего поручения.

Изолированный `agent_test` Student на DEV проводит текстовые реплики через живой
AI/Foundation pipeline и возвращает состояние PostgreSQL. BB агент проверил
естественное знакомство, прямую запись Trial, уточнение нагрузки и исправление цели.
Ложное обещание сохранить нагрузку/Homework исправлено в текстовой политике и
повторно проверено; поля останутся Unknown до Trial. При исправлении цели запись
прошла, но ответ один раз деградировал до шаблона из-за provider 429.
Первые два Telegram запроса на Trial не создали запись: сначала были отвергнуты
аргументы booking, затем модель дважды неверно вызвала onboarding update.
Следующий живой повтор вернул буквальный `placeholder` без вызова инструмента.
После исправления и поставки AI service прямой запрос создал booking: безопасная
[диагностика](https://github.com/greenblat17/1billion-dollar-startup/actions/runs/44091174219)
показала `booking_present=true`, а Telegram показал кнопку входа. Один отклонённый
неизвестный инструмент перед успешным booking остаётся предметом диагностики.
Полная [матрица AC](acceptance/dev-waves-1-9-matrix.md), [диалоги](acceptance/runs/text-ac-results.md)
и [следующие интерфейсы приёмки](acceptance/remaining-ac-execution.md) сохраняют
отдельные статусы `pass/fail/blocked`. Описание адаптера: [агентская приёмка](integrations/agent-acceptance.md).

## Совместная интеграция волны 9

SP-20 запускает Review после завершённого обычного урока. Он читает сохранённые
Observations, предложения Problems и Stage Score, не вводя вторых формул. При
`masteryReady` и реальном пригодном evidence переводит Task в Mastered с историей;
незавершённые Tasks остаются. Сохранённый Review даёт следующему уроку контекст
предыдущей работы и запускает обновление приоритета SP-09. Повторное событие
проверяется по ревизии источников. Текст заметок берётся из живого источника,
чтобы не сохранять его дольше срока хранения.

SP-17C пока не выдаёт утверждённый вывод Known/Not Known. SP-20 не меняет Topic
Knowledge без него. SP-11B и SP-21 остаются вне волн 1–9. После объединения прошли
821 Python tests, Kotlin unit, `buildFatJar` и `detekt`. CI применил V64 и прошёл
127 PostgreSQL integration tests, Redis migration rehearsal и HTTP-проверки.
Живой цикл урока и качество решений модели ещё не подтверждены.

## Совместная интеграция волны 8

Обычный Mini App урок получает текущую Task, Plan, Stage и Score. При наличии
подготовленного проверенного Content admission выдаёт его для SP-14/15 Voice Actions;
иначе урок остаётся разговорным. Мягкий ориентир 30 минут: Conversation 15,
Practice 13, Wrap-up 2. Сохранённая работа переводит Task в In Progress; ранний
конец сохраняет незавершённое. Свободная беседа создаёт SP-17 Observation для SP-18
лишь при подтверждённой каталогом паре Topic/Problem и пригодном evidence. Новая
Task не создаётся до сохранённого ответа ученика; SP-09 применяет согласие или отказ.
При неясном результате записи Observation повтор с тем же ID выполняется один раз,
после чего урок останавливается с кодом явного восстановления.

После объединения прошли 819 Python tests, Kotlin unit, `buildFatJar`, `detekt`
и 16 Mini App tests. PostgreSQL интеграция SP-16, живой Voice/Telegram и качество
решений модели не проверены. Post-Lesson Review добавлен в волне 9.

## Совместная интеграция волны 7

SP-11A доставляет сохранённую первую Roadmap в Telegram после Trial без проверки
подписки. При неизвестном результате отправки повтор останавливается. SP-19B
пересчитывает Stage Score и Task Progress по текущим Observations, включая исправления,
удаление источников и новую ревизию Plan. SP-18 считает отдельный экспериментальный
Problem Score вне активной Task и выдаёт предложение, но не создаёт Task автоматически.

После объединения прошли 813 Python tests, Kotlin unit, `buildFatJar` и `detekt`.
Новые PostgreSQL тесты SP-11A/19B/18 локально не запущены: host исчерпал SysV
shared memory IDs. CI применил V61–V63, прошёл 126 PostgreSQL сценариев и
миграционный прогон после исправления устаревшего импорта тестовой модели.
Реальная Telegram-доставка, голосовой урок, качество оценок и DEV/PROD не проверены.
SP-16 ещё должен создавать free-conversation Observations для SP-18 и спрашивать ученика
о новой Task.

## Совместная интеграция волны 6

SP-09 хранит приоритеты отдельно от исходной Roadmap и принимает согласие либо отказ
ученика по предложенной Problem. SP-10 даёт осторожный диапазон сроков по согласованной
нагрузке и источнику Cambridge; каждый Skill имеет отдельный старт, самооценка отмечена
как предварительная. SP-17C переводит результаты Teaching Actions в формат SP-17A
и записывает их после сохранения реплики. Он передаёт SP-20 историю Topic evidence,
но пока не меняет Topic Knowledge без согласованного правила достаточности.

После объединения прошли 809 Python tests, Kotlin unit, `buildFatJar` и `detekt`.
Новые PostgreSQL тесты SP-09/17C локально не запускались: host исчерпал все 32 SysV
shared memory IDs. CI проверяет миграцию V60 и транзакционные сценарии; предыдущие
прогоны остановили три неточные проверки времени Telegram introduction. SP-16 ещё
должен подать Content в обычный урок; SP-19B должен читать реальные Observations.
Живая педагогика и срок достижения цели не подтверждены.

## Совместная интеграция волны 5

SP-08 создаёт первую персональную Roadmap после SP-07 Assessment: четыре MVP Skills,
отдельные стартовые уровни, явные гипотезы, сохраняемые версии и Tasks с начальным
Progress 0%. SP-14 исполняет Explanation/Exercises, SP-15 — Exposure/Conversation.
Оба передают предложения evidence без расчёта Stage Score. Обычный Voice Agent имеет
hook для выданного Content, но SP-16 ещё должен передать его при admission и вести урок;
SP-17C должен сохранить результаты как Observations.

После объединения прошли 783 Python и 328 Kotlin unit tests, `buildFatJar` и `detekt`.
Новый PostgreSQL тест Roadmap зарегистрирован в отдельном integration suite; локальный
PostgreSQL сейчас не стартует из-за общего лимита shared memory. Миграция V59 и
сквозной Roadmap ещё требуют PostgreSQL/HTTP проверки. Живая модель и голос не проверены.
Решения и ограничения: [implementation-decisions.md](../implementation-decisions.md).

## Совместная интеграция волны 4

SP-07 запускает отдельную диагностику после успешного `trial.observe`. Она сохраняет
предварительные CEFR и Topic Knowledge через SP-03; системный Listening остаётся Unknown.
SP-13B готовит материал по Task/Stage через SP-13A и отклоняет его при неудачной
семантической проверке. SP-19A хранит Stage Score и Task Progress по типизированным
fixture-событиям; реальный Observation consumer остаётся SP-19B.

Локально после объединения прошли 737 Python, 328 Kotlin unit, 121 PostgreSQL integration
и 15 HTTP тестов Python → Ktor → PostgreSQL. `buildFatJar`, `detekt` и миграция V58
прошли. Тестовая PostgreSQL 14.10 и Ktor harness использовались изолированно.
Живая модель, Voice Agent, Telegram, DEV/PROD и сквозной Roadmap не проверены.
CI волны 4 после закрепления PostgreSQL-клиента 16 прошёл сборки, но три старых
Telegram/PostgreSQL теста исчерпали минутный `runTest` timeout. Причина, вероятно,
в округлении положительного ожидания менее 1 мс до нуля. Цикл исправлен,
точный unit-тест добавлен; повторный CI ещё требуется.

## Совместная интеграция волны 3

Локально прошли 722 Python unit, 324 Kotlin unit, 119 PostgreSQL integration и 15 HTTP
сценариев Python → Ktor → PostgreSQL. Дополнительный HTTP тест проверил передачу StageRef
из реального SP-12B Plan в SP-13A Content. `buildFatJar`, `detekt` и Flyway V55 прошли;
тестовый Ktor и отдельная PostgreSQL 14.10 остановлены. Каталог и ответы модели в проверках
синтетические; качество живой классификации Trial и генерации материала не подтверждено.

В Notion SP-12B подтверждены 3/5 AC (адаптация во время урока и завершение Stage ждут SP-16/19),
SP-17B — 4/5 (потребление SP-07 ещё не реализовано), SP-13A — 5/5 на уровне контракта.
Галочки не означают готовности всей SP-12, SP-13 или SP-17.

## Новый SP-13A: Learning Content Contract

После интервью пользователь явно разрешил реализацию 2026-11-17. Изолированная ветка
`bb/sp-13a-content-contract-thr_i92t3guqfy` основана на `2487b22` из draft PR #58;
её результат включён в PR, но ещё не поставлен на DEV/PROD.

- V55 хранит Content конкретной Task и исторического Plan/Stage, неизменяемые версии текста,
  выдачу в regular Lesson и отметку дефектной версии. Новая подготовка требует текущий StageRef;
  старое выданное задание остаётся читаемым после изменения Plan.
- Один документ покрывает Explanation, Exercises, Exposure и Conversation с кратким голосовым
  условием, форматом действия и допустимыми вариантами ответа. Это описание материала;
  SP-13A не оценивает фактический ответ ученика и не меняет Plan, CEFR, Stage Score или Progress.
- Структуру и Task/Stage ownership проверяет Ktor. Запись пока только operator, чтение в student
  scope; Python FoundationClient предоставляет адаптеры. `validation.status=passed` от trusted
  producer требуется для выдачи, но реальный AI semantic validator ещё принадлежит SP-13B.
- Personal forget удаляет сохранённый материал, сохраняя Task/Plan. Full learning reset,
  новый `/start` и student.delete удаляют его каскадом от Task. Legacy lesson plan не менялся.
- Локально прошли 705 Python tests, 324 Kotlin unit tests, 118 PostgreSQL integration tests,
  2 новых HTTP Python → Ktor → PostgreSQL tests; `buildFatJar`, `detekt`, миграция V55 на
  отдельной PostgreSQL 14.10 и проверка JSON-примеров. Тестовый каталог и Content синтетические.
  Живая генерация/голос/ASR, SP-14/15 execution, SP-17C Observations, Score, DEV/PROD и
  пользовательская приёмка не проверены.

[Контракт и handoff](integrations/learning-content.md), [план и evidence](plans/2026-11-16-sp13a-content-contract.md),
[синтетические примеры](../content-contract/v1/examples.json).

## Совместная интеграция волны 2

SP-06, SP-17A и SP-12A собраны от `7e3800b` и добавлены в общую ветку
`bb/sp-02-05-v5-5` / draft PR #58. Три исходные ветки сохранены. V52–V54 применены к отдельной PostgreSQL 14.10.
Observation StageRef разрешается по исторической ревизии реального Learning Plan.

Совместно прошли 705 Python, 321 Kotlin unit, 115 PostgreSQL integration, 44 HTTP integration и
16 Mini App tests; `buildFatJar`, `detekt` и Mini App build прошли. HTTP проверял Python → Ktor → PostgreSQL.
Старые HTTP-сценарии образовательного Trial проверены на синтетическом 15-минутном legacy-уроке,
новый 30-минутный Trial — отдельным сценарием. Это не перенос пользовательских записей.
Педагогический каталог опубликован только как синтетический test fixture.

Интеграция опубликована в draft PR #58; merge и deploy не выполнялись. Живой звонок, Telegram, S3, визуальная приёмка,
DEV/PROD и полная цепочка Trial → Assessment → Roadmap не проверены.

## SP-17B: Trial → Observations

После утверждения пользователем SP-17B реализован в отдельной ветке от `2487b22` и включён в draft PR #58.
Завершение Trial v5.5 ставит отдельный `trial.observe` job. Python extractor читает
сохранённые raw events и опубликованный каталог, связывает точные цитаты с
`sourceEventId/sourceSequence` и возвращает только Speaking, Grammar, Vocabulary.
Ktor применяет batch через SP-17A `LearningObservations` в одной транзакции,
сохраняет ревизии, отклоняет устаревший результат и не допускает Listening.
`GET /trial/events` показывает `observationStatus`; `assessmentStatus` теперь
обновляется SP-07. Listening после Trial имеет системный CEFR Unknown;
самооценка хранится отдельно. Ни Roadmap, ни Plan, ни Content не создаются.

Локально прошли Python unit suite (708), новый extractor suite (4),
Kotlin unit (321), PostgreSQL integration (116) и HTTP Python → Ktor → PostgreSQL (1)
с фиксированным результатом модели. Fat JAR, detekt, относительные ссылки документации
и `git diff --check` прошли. Новая Flyway миграция не нужна: используются V52/V53.
Merge, deploy и живой Realtime/Telegram/S3 не выполнялись.
Качество классификации естественной речи не доказано синтетическими тестами.

[План и сценарии](plans/2026-11-16-sp17b-trial-observation-integration.md).

## Новый SP-06 v5.5: Trial Lesson

План утверждён пользователем 2026-11-16. Работа началась в отдельной ветке
`bb/sp-06-trial-lesson-v5-5-thr_tvbpxhm5dn` от `7e3800b`; результат включён в draft PR #58.

- Новый Trial: 30 минут подключённого времени, RU warm-up → EN conversation → RU wrap-up.
  Мягкие фазы и таймер сохраняются между подключениями; модель не должна называть CEFR/баллы/сроки.
- Reconnect возможен до конца исходного слота; остаток времени можно использовать после его конца.
  Кнопка завершает Trial окончательно; оставшийся отключённым урок закрывается как прерванный.
- Native tool сохраняет intensity/Homework через SP-03 с цитатой, source ownership и revision.
  Нет разрешения писать CEFR, Goal, Topics или расписание. Unknown/null и Homework no допустимы.
- V52 хранит clock/phase и raw call metadata. Scoped GET events/audio доступны будущему SP-17B.
  Stable IDs/revisions, nullable ASR confidence, реальные audio references; детектор ASR ошибок отсутствует.
- Новый Trial отделён от старого educational evaluation/Roadmap. Personal Memory extraction сохранён.
  Полная цепочка Assessment → Roadmap → Telegram ещё не реализована; SP-06 не закрывает её приёмку.
- Локально: Python 701 passed; Kotlin 309 unit + 96 PostgreSQL integration passed;
  после последнего ограничения read-scope повторно прошли 7 Trial integration tests.
  HTTP Python → Ktor → PostgreSQL: 11 passed. Mini App: 16 tests + production build passed.
  `buildFatJar`, `detekt`, `git diff --check` и локальные ссылки проверены.
- Browser Automation не открылся из-за `EATTESTATIONVERIFY`; визуальная приёмка не подтверждена.
  Живая 30-минутная модель/Realtime/Telegram/S3 приёмка, DEV readiness и доставка Roadmap не проверены.
- Обязательное последующее удаление старых обработчиков и их потребители перечислены в
  [контракте/handoff](integrations/trial-v55.md#handoff-и-старый-pipeline); интеграцию ведёт родитель.

[Утверждённый план](plans/2026-11-16-sp06-trial-lesson-v55.md). В Notion 12 AC отмечены по проверке кода; общий статус In progress.

## SP-12A v5.5: Learning Plan Contract

Реализован локально после явного утверждения в отдельной ветке `bb/sp-12a-learning-plan-contract-thr_kdrsx7xmdj`,
от `7e3800b` общей SP-02–05. Результат включён в draft PR #58.

- V54 хранит Plan конкретной Task, immutable definitions и историю текущего Stage.
  Четыре Teaching Methods; один Stage на выбранный method. Binary condition без веса;
  numeric weights неотрицательны и в сумме 1 при наличии numeric stages. Ненужные методы отсутствуют.
- Создание/ревизия атомарно связывает Plan с Task, не меняет Skill/Topic/Problem, цель, lifecycle или Progress.
  Task без Plan допустима. Generic task.update проверяет владельца, Task и текущую Plan revision.
- StageRef = taskId/planId/planRevision/stageId; historical resolver сохраняет старую привязку после reorder/reweight/removal.
  Смена method/kind/condition требует нового ID. Cursor revision отделена от definition revision;
  повтор/возврат/выбор ученика не сбрасывает результаты и не создаёт новую попытку прохождения.
- Internal API и FoundationClient: чтение в student scope, запись только operator, общий lock/receipts/revisions.
  Нет новых native tools. SP-12A не выбирает методы по Problem, не создаёт content и не считает Score.
- Plan не содержит личного текста/контента. Personal forget и retention сохраняют структуру;
  learning.forget/reset/delete/replay удаляют Plan/revisions/history каскадом от Task.
- Проверки: 697 Python, 314 Kotlin unit, 95 PostgreSQL integration, 20 реальных HTTP tests — passed.
  `buildFatJar`, `detekt` — passed. HTTP проверяет Python → Ktor → PostgreSQL и регрессии SP-02–05;
  7 новых HTTP-сценариев и 6 новых PostgreSQL-сценариев относятся к SP-12A.
  После дополнения /start и deletion replay с заполненным Plan все 14 Plan/Task PostgreSQL-сценариев повторно passed.
- V54 применена только к отдельному временному PostgreSQL 14.10; каталог синтетический, без пользовательских данных.
  StageRef подключён к SP-17A в общей ветке. SP-13A/19A получают документированный контракт и fixtures,
  это не подтверждение будущей AI/Voice/Score интеграции. DEV/PROD, merge и deploy не выполнялись.

[Контракт и handoff](integrations/learning-plan.md), [утверждённый план](plans/2026-11-16-sp12a-learning-plan-contract.md).

## SP-12B v5.5: педагогическая логика

После интервью план утверждён пользователем. Реализация из отдельной ветки
`bb/sp-12b-pedagogical-logic-thr_fi3xp7cvaj` от `2487b22` включена в draft PR #58.

- Python `PedagogicalPlanner` проверяет реальную Task и опубликованный каталог, затем создаёт SP-12A Plan
  по одному из шести утверждённых базовых шаблонов. Методы и веса заданы заранее. Если шаблона нет,
  возвращается `template_missing`; Task остаётся без Plan. Существующий Plan повторно не создаётся.
- StageRef сохраняет Task/Plan/revision/Stage identity. Правило переходов использует готовый SP-19 результат:
  выполненный binary requirement или numeric Score ≥85 двигает cursor на соседний Stage;
  Score ≤15 даёт сигнал пересмотра и допускает явный возврат. Отказ оставляет cursor без изменений.
  Score, Progress, completion и попытки SP-12B не выставляет.
- Локально: полный Python набор — 717 passed, 1 стороннее предупреждение deprecation;
  8 HTTP сценариев Python → Ktor → PostgreSQL passed, включая шесть реальных Plan и переходы.
  Kotlin `:server:test :server:buildFatJar detekt` passed, 321 server unit tests без ошибок.
  PostgreSQL 14.10 и каталог использовались только как отдельные синтетические тестовые ресурсы;
  test harness и БД остановлены. Новых Flyway миграций нет.
- В живой урок, Teaching Actions и Stage Score модуль пока не подключён: SP-16/19 и
  последующие Action integrations впереди. Listening Trial не оценивается и не даёт Listening Observation
  для автоматического Plan. DEV/PROD и живое качество обучения не проверены; merge/deploy не выполнялись.

[Правила и handoff](integrations/learning-plan.md#sp-12b-педагогический-выбор-и-переходы),
[утверждённый план](plans/2026-11-16-sp12b-pedagogical-logic.md).

## Совместная проверка SP-02–05

В общей ветке прошли 695 Python, 309 Kotlin unit, 89 PostgreSQL integration и 13 HTTP integration-тестов.
`buildFatJar`, `detekt` и проверка diff прошли. HTTP проверял Python → Ktor → PostgreSQL,
включая создание Learning Task и запись профиля при знакомстве. PostgreSQL 14.10 и каталог были
отдельными синтетическими тестовыми ресурсами; тестовые процессы остановлены.
Педагогическое содержание Topic Map ещё ждёт приёмки; реальный каталог не опубликован.
Живой диалог модели, Telegram, DEV/PROD и 30-минутный Trial не проверялись и не поставлялись.

## Новый SP-17A: Observation Contract & Core

План утверждён пользователем 2026-11-16. Реализация в отдельной ветке
`bb/sp-17a-observation-contract-thr_3yyacry6cr` от `7e3800b`; результат включён в draft PR #58.

- Единый evidence journal: источники и spans, Skill/nullable Topic/Problem, optional Task/Stage/Action,
  independent/error/self-corrected/hinted, явные признаки качества и происхождение.
- Устойчивые identities, защита retry/concurrent writes, revisions и invalidation без второй попытки.
  Unknown classification не получает выдуманных ID. Нет автономного CEFR, Topic Knowledge или Score.
- Внутренние API и Python types/client; запись operator-only, без новых native tools и production extractor.
  Старый post-call analysis сохранён. SP-03 принимает `learning_observation` sources, включая все исходные event IDs.
- V53 согласована с родителем, независима от V52/V54. Stage resolver — integration port;
  в общей ветке он подключён к историческому Plan SP-12A. Binary Stage не становится numeric evidence.
- Privacy, retention и полный reset/delete включают новый журнал и его историю.
- Проверки: 697 Python tests, финальные 316 Kotlin unit, buildFatJar и detekt passed.
  Полный PostgreSQL regression — 99 tests passed; после уточнения source availability и добавления
  reset/replay сценариев все 12 Observation integration tests повторно passed.
  36 реальных HTTP checks Python → Ktor → PostgreSQL passed, включая новый журнал и прежние consumers.
  JSON handoff examples проверены backend validator и Python adapter; ссылки и diff проверены.
- V53 применяется только к отдельному временному PostgreSQL 14.10 с синтетическими данными.
  Тестовые backend и PostgreSQL остановлены после проверки.
  Реальные Trial→Observation (SP-17B), Actions (SP-17C), live scoring и качество tagging/ASR не проверены.
  Детектор ошибок ASR не добавлен. В Notion отмечены 5 AC дочерней SP-17A; основная SP-17 остаётся открытой. Merge/deploy не выполнялись.

[Утверждённый план](plans/2026-11-16-sp17a-observation-contract.md),
[контракт и handoff](integrations/learning-observations.md).

## Новый SP-04 v5.5: Learning Tasks

Реализован после явного утверждения плана; изменения включены в общую ветку SP-02–05.

- Персональная Task связывает Goal revision + Skill + Topic + Problem. Проверяются опубликованный каталог,
  совместимость Skill и принадлежность цели ученику. Создание всегда Identified/0%, Plan/Actions/Homework необязательны.
- Lifecycle Identified → In Progress → Mastered → Reopened → In Progress, внешний Progress 0–100,
  nullable priority и поздние ссылки сохраняются с устойчивым ID и историей before/after.
- Повторное создание возвращает существующую Task, включая Mastered; разные Problems одного Topic независимы.
  Student lock, revisions, receipts и DB constraint защищают конкурирующие записи и повторы.
- Внутренние API и FoundationClient доступны; запись только operator, без новых AI tools.
  Task Progress не меняет Topic Knowledge; SP-04 не рассчитывает освоение, порядок или Learning Plan.
- Personal forget убирает свободные пояснения истории, сохраняя учебную работу и Progress.
  Полный learning.forget, delete, новый /start и deletion replay удаляют Tasks и историю.
- Проверки: 692 Python tests, 309 Kotlin unit, buildFatJar и detekt — passed.
  12 реальных HTTP checks (Python → Ktor → PostgreSQL) — passed, без моделей и пользовательских данных.
  Полный PostgreSQL-прогон: 88 tests — passed; включает 8 новых Task-сценариев.
  После дополнения проверки завершения урока все 8 Task-сценариев повторно passed.
- V51 применялась только к отдельному временному PostgreSQL 14.10. Каталог публиковался как синтетическая тестовая версия.
  DEV/PROD и живой диалог не проверялись.
- Следующие SP: генерация и адаптация Roadmap, Plan/Stages, Observations, scoring, уроки, Homework и UI.
  В общей ветке SP-12A проверяет Plan (см. выше); Action/Homework refs остаются
  непрозрачными идентификаторами до реализации их доменов.
  Знакомство SP-05 не создаёт Tasks. Смена цели не перестраивает их автоматически.

[Утверждённый план](plans/2026-11-16-sp04-learning-tasks.md), [контракт](../architecture.md#learning-tasks-sp-04-v55).

## Новый SP-05 v5.5: Telegram Onboarding

Реализован локально после утверждения плана; изменения включены в общую ветку SP-02–05.
План утверждён пользователем 2026-11-16; уточнения состава знакомства — в конце продуктовой v5.5.

- Native tool `update_onboarding` сохраняет цель, текущую общую самооценку, target CEFR и дедлайн через SP-03.
  Backend допускает только эти поля, проверяет текущий источник/цитаты, revision, scope и receipts.
  Системный CEFR, Topics, Tasks и Roadmap не меняются; новая миграция не требуется.
- Знакомство помогает выбрать A1–C1 по коротким описаниям. Unknown/отказ допустимы;
  трудности остаются пользовательскими фактами, не диагнозом. История обучения отдельно не собирается.
- Частота/длительность и Homework обсуждаются на Trial (SP-06). По уровню или цели не отказываем,
  результат не гарантируем. После записи разговор завершается до инициативы ученика.
- На срезе SP-05 Trial был 15 минут; локальный SP-06 выше переводит новые записи на 30 минут.
- Проверки: 693 Python tests, Kotlin unit, buildFatJar и detekt — passed.
  HTTP-сценарий Python → Ktor → PostgreSQL проверил запись профиля, немедленный Trial,
  replay без повторного вызова модели и передачу профиля в голосовой контекст.
  Модель в HTTP-проверке подставная. Полный PostgreSQL-набор: 80 прежних тестов passed, новый тест сначала упал на неверном ID фикстуры;
  после исправления весь StudentLearningIntegrationTest прошёл повторно.
- Живой модельный/Telegram-прогон не выполнен; OPENAI_API_KEY в окружении не настроен.
  Естественность диалога и реальная передача в звонок остаются границей приёмки.
  Deploy не выполнялся.

## Новый SP-03 v5.5: учебное состояние

Утверждённый foundation реализован локально поверх `32de1e0` в ветке `bb/sp-02-v5-5-thr_7zefuwzvsp`.

Локальная правка после `3eddb89`: Homework Minutes допускает ноль; положительное значение по-прежнему требует day/week.
Частота и длительность уроков требуют положительных чисел. Новый unit-тест, `:server:test`, `:server:buildFatJar`, `detekt` — passed.
После этой правки совместный PostgreSQL-прогон SP-02–05 прошёл; DEV/PROD не менялись.

- Student Learning State отделён от персональной Memory. Цель имеет одного владельца; старые Memory-writers учебных полей отключены.
- Текущий CEFR: общий и по Skills, отдельно самооценка и Speaky; целевой CEFR только общий. Значения A1–C1/Unknown, без диапазонов.
- Topic Knowledge: четыре состояния, история изменений, объяснения и проверяемые ссылки на источники; оценка закреплена за версией каталога.
- Интенсивность и Homework Preferences структурированы, неизвестные числа — null. Чтение не создаёт оценок и не меняет данные.
- Внутренние API, FoundationClient и отдельный ограниченный контекст обслуживают потребителей. Общая запись требует operator scope; SP-05 добавила ограниченную запись пользовательских полей при знакомстве.
- Повторы, версии, ownership, сохранение между сессиями, retention, явное удаление и `/start` покрыты проверками.
  Истечение срока транскрипта сохраняет учебную оценку; новые таблицы включены в удаление и replay deletion ledger.
  Personal forget очищает зависимости исходных сообщений, сохраняя независимые поля и оценки.
  Три регрессии покрывают историю источников, частичную очистку, источники цели и сохранение связи через удалённый Observation.
  Полный сброс требует learning.forget/reset/delete; у гипотез без источников стирается только объяснение.
- Локально: **690 Python tests; 305 Kotlin unit; полный PostgreSQL-прогон 80 tests;
  31 HTTP tests** (10 foundation/catalog/student-state + 21 roadmap/memory/progress) — passed.
  `:server:buildFatJar`, `detekt`, `git diff --check` — passed. PostgreSQL 14.10 был отдельным временным кластером;
  каталог опубликован только как синтетическая тестовая версия. Сохраняется прежний Python dependency warning.
- **Остаётся следующим задачам:** AI-заполнение через Post-Trial Assessment/Post-Lesson Review, педагогические переходы,
  новый producer Observations, выбор Learning Tasks и UI. Нет механизма оспаривания Topic Knowledge, числового mastery и переноса старых учеников.
- V50 применялась только к тестовым БД. Реализация SP-03 входит в общую ветку SP-02–05. DEV/PROD и живой диалог не проверялись.

[План](plans/2026-11-15-sp03-student-learning-state.md), [контракт и границы](../architecture.md#student-learning-state-sp-03-v55).

## Новый SP-02 v5.5: учебные каталоги

Реализация сохранена в коммите `1166f02`; это отдельная задача от прежнего SP-02 про trial.

Локальная правка после `3eddb89`: новый импорт защищает опубликованные ID от изменения смысла
Skill/Problem и от изменения Skill, CEFR, objective, scope или coverageArea Topic. Названия и метаданные ревью можно уточнять.
Удаление опубликованного ID из новой версии отклоняется.
Черновики можно корректировать новой версией. Три catalog integration tests прошли на отдельном временном PostgreSQL;
повторно прошли `:server:test`, `:server:buildFatJar` и `detekt`. Реальный каталог не публиковался.

- Skill Map: шесть навыков; Speaking, Listening, Grammar и Vocabulary входят в MVP.
- Topic Map: 163 целевые темы A1–C1. Источник — `learning-catalog/v1/catalog.json`, schemaVersion 2, версия `speaky-v55-v2`.
- Problem Map: девять типов проблем. Reading/Writing сохранены в модели, без наполнения Topics.
- Prerequisites и Curriculum Order исключены решением пользователя. Зависимости и порядок определяет будущая Roadmap Generation.
- Backend реализует валидацию, версионный импорт в PostgreSQL и отдельную публикацию с записью приёмки.
  Внутренний API читает опубликованный каталог; Python использует FoundationClient. V48/V49 не применялись к пользовательским базам.
- После изменения формата прошли 3 Kotlin catalog unit, 3 Python adapter, 73 PostgreSQL integration и 8 HTTP integration tests;
  buildFatJar, detekt и проверки генерации прошли. Это локальные проверки, не DEV/PROD-поставка.
- [Темы и ожидаемые способности](product/2026-11-15-learning-catalog-review.md) ожидают содержательной приёмки.
  Персональные Learning Tasks/Roadmap не входят в SP-02. Deploy не выполнялся.

## Новый продуктовый ориентир

[Спецификация v5.5](product/2026-11-14-personalized-learning-v5.5.md) добавлена как актуальная целевая модель. [v0.5](product/2026-11-08-ai-tutor-experience-v0.5.md) и планы SP-00–SP-08 сохранены как исторические источники. Добавление документа не меняет runtime и не подтверждает соответствие кода новой модели.

Главные расхождения с описанным ниже состоянием: trial теперь 30 минут (RU warm-up → EN conversation → RU wrap-up), без CEFR и чисел во время звонка; после анализа полная Roadmap отправляется каждому ученику до обсуждения расписания. Regular теперь от 30 минут с Conversation → Roadmap Practice → Wrap-up. Целевая учебная система вводит Skill/Topic/Problem Map, Learning Tasks и Plans, согласуемое Homework, Observations, Problem/Stage Score и Task Progress. Числовые параметры v5.5 обозначены как экспериментальные. Реализация, миграция данных, DEV-поставка и живая приёмка этих изменений не выполнялись в рамках добавления спецификации.

PR #57 объединён в dev (`c2c988e`). Приветствие, стратегии диалога, запись, редизайн виджета без caption, вход в урок и исправление `reply_parameters:null` поставлены на DEV: [43442380355](https://github.com/greenblat17/1billion-dollar-startup/actions/runs/43442380355), success, `44f7e03`. Реализация, workflow поставки и живая приёмка — разные доказательства.
Локально разделены немедленный старт и будущая запись по `timing`, без поиска фраз. После успешного `now` отправляется только durable-приглашение «Начать урок»; виджет, календарь и обещание напомнить подавлены, в историю не попадает несказанное прощание. Ясный запрос направляет модель вызвать tool в текущем ходе без второго согласия; после успешного действия лишнего вызова модели нет. Новая правка ещё не поставлена. Проверки: 678 Python, 300 Kotlin unit, 2 PostgreSQL-сценария (создание/перенос now, replay, рестарт worker, unknown send, история; будущие напоминания), buildFatJar, detekt — passed. Полный PostgreSQL-прогон прерван, его результат не подтверждён. Живой модельный/Telegram-прогон не выполнялся; выбор намерения живой моделью остаётся границей приёмки.
Исправлены ошибки DEV-диалога: режим trial `after_minutes`, взаимоисключающие схемы времени, пропуск неподтверждённых полей optional memory, завершение после двух отказов tools без новых действий и совместимые ключи personal candidates. Проверки: 673 Python tests и `git diff --check` — passed. Реальные модельные проверки не запускались.
Переделан HTML-виджет записи: компактная дата и диапазон времени, одна кнопка календаря, подтверждение перед виджетом и завершение после него. Утреннее напоминание без кнопок; вход в урок — в момент начала. Проверки: 297 Kotlin tests, buildFatJar, detekt, 673 Python tests — passed. Визуальная проверка — локальный Chrome, не Telegram. Ранее отправленные виджеты сохраняют старый HTML, их данные поддержаны.
Mini App ждёт начала по серверному времени, учитывает перенос/отмену, затем автоматически подключается; ошибки микрофона объяснены. Trial-напоминания: 08:00 и за час без кнопок, приглашение с «Начать урок» в начале, мягкая неявка через 10 минут. Проверки: 13 Mini App, 674 Python, 298 Kotlin unit; PostgreSQL — 67/69 в полном прогоне, два устаревших ожидания обновлены и повторно прошли вместе с новым тестом раннего входа (3/3). buildFatJar, detekt, Mini App build и diff-check — passed. Живой Telegram/голосовой прогон агентом не выполнялся.
Вопрос приветствия заменён на «Расскажи, пожалуйста, для чего ты изучаешь английский?» в коде, тесте и UX-документах.
Доработка в ветке PR #57: каждый `/start` сбрасывает ученика во всех окружениях (V47), включая память, историю, уроки, прогресс и подписку. Replay команды не сбрасывает повторно; подготовка персонального урока не менялась. Сброс поставлен на DEV: [43418840976](https://github.com/greenblat17/1billion-dollar-startup/actions/runs/43418840976), `7b2dbbd3f9a79964e72d3f24edf5ab5172c6fd29`; backend, AI, Mini App и readiness — success. Проверки сброса: 295 Kotlin unit и 68 PostgreSQL integration, 14 Python prompt/context tests — passed; buildFatJar, detekt и diff-check — passed.

## Доработка Mini App

Ветка `bb/mini-app-impeccable-thr_gbnc9pzkdq`, база `origin/dev` `c2c988e`.
После согласования critique/shape реализованы круглые кнопки звонка, иерархия готового прогресса,
компактные ошибки Immersive Dark с раскрываемой помощью и одним основным действием.
В Warm Editorial переработаны шесть состояний прогресса, ожидание с аватаром/временем,
календарь и карточки встреч. Состояния/доступность действий остаются серверными;
подписка, даты/пояса, автоподключение, voice transport и календарный экспорт не изменены.
Сохранены исходный стайл-гайд, аватар, шрифтовой стек и палитра.
Adapt/audit: rem, safe-area отступы, main landmarks, фокус после переходов и корректный reset состояний.
Локально: 16 Mini App tests, build и `git diff --check` — passed; независимый review без блокеров.
Детектор отмечает утверждённый Inter и статические icon/padding warnings; подтверждённых дефектов по ним нет.
Новая отрисовка и Telegram safe areas **не приняты агентом визуально**: headless browser заблокирован
`EATTESTATIONVERIFY`, in-app browser host недоступен, доступ к Chrome не согласован.
Галерея с маркированными фикстурами находится отдельно в thread storage и не входит в production build.
Деплой и живые вызовы не выполнялись. Финальная визуальная проверка остаётся открытой.

## Реализовано в коде

| Область | Доступное поведение |
| --- | --- |
| SP-00 | PostgreSQL foundation, единый профиль/контекст, scopes, версии, receipts, jobs и удаление; legacy изолирован |
| SP-01 | Постоянный Telegram-диалог, native tools, trial create/read/reschedule/cancel/rebook; entities; общий характер Kora и отдельные стратегии знакомства/обычного диалога; первое приветствие из четырёх сообщений с паузами/typing и отменой остатка при входящем ответе |
| SP-02 | Отдельный Mini App, Realtime/Marin, trial, reconnect, playback/truncation, S3-архив и Telegram follow-up |
| SP-03/04 | Подготовка и проведение regular 15–60 минут, согласование расписания, переносы/отмены, DST-уточнения, reminders и no-show |
| SP-05 | Opt-in mock checkout, тестовое продление, отмена автопродления, entitlement gate для regular; trial бесплатен |
| SP-06 | Roadmap после анализа, адаптация по источникам, подготовка следующего урока, явные terminal failures |
| SP-07 | Общая память text/voice/learning, выборка, запреты упоминания, спорные наблюдения, забывание с отзывом контекста |
| SP-08 | Качественный прогресс по свидетельствам и My Progress без звонка, микрофона или подписки |

Контракты и исходники: [архитектура](../architecture.md), [карта планов и справочников](README.md).
CMP Android/iOS/Desktop остаётся отдельным прототипом; его API не подключён к shipping runtime.

## Поставка и проверки

- DEV redeploy знакомства/записи [43417154588](https://github.com/greenblat17/1billion-dollar-startup/actions/runs/43417154588),
  `d6e8ed616f2b9c7e25482a8f37c2c27b785ce401`: success, включая backend, AI, Mini App и readiness.
  Последующая поставка сброса V47 указана выше. PROD не затронут.
  PR в `dev`: [#57](https://github.com/greenblat17/1billion-dollar-startup/pull/57); merge не выполнялся.
- На `fce9b62` AI service, CMP и Mini App checks — success. **Foundation integration — failure**:
  [43304873405](https://github.com/greenblat17/1billion-dollar-startup/actions/runs/43304873405),
  57 тестов, один сбой `realBackupRestoreReplaysDeletionLedgerBeforeServingContext`
  (`IllegalStateException`, `FoundationIntegrationTest.kt:164`). Причина здесь не установлена.
- Текущая локальная проверка знакомства и записи: **666 Python, 295 Kotlin unit, 68 PostgreSQL integration — passed**;
  `:server:buildFatJar`, `detekt` и `git diff --check` — passed. Python сообщает прежний dependency warning.
  PostgreSQL был отдельным локальным кластером с синтетическими данными; Telegram/model delivery — подставные.
  Восемь новых DB-сценариев проверяют четыре реплики, прерывание после 1/2/3 сообщений и до первого,
  порядок intake/history, replay/рестарт, конкурирующие claims, сброс по новому start, replay без повторного сброса, миграции V45/V47,
  typing/send failures и удаление. Новые проверки записи покрывают receipt виджета после рестарта,
  запрет устаревшего подтверждения, morning/DST/совпадения, перенос/отмену, unknown delivery,
  ownership календаря, UTC/UID/SEQUENCE/UTF-8 ICS и вход в календарь без микрофона. Python проверяет контекст прерванного приветствия и replay без model call,
  состав промптов обоих режимов, сохранение Kora в обычном диалоге и core-самооценки при сбое selector.
  Backend проверяет явный textMode и отсутствие клавиатуры в знакомстве. Семантическое качество живой модели не проверено.
  Локальный backup/restore test также прошёл; это не отменяет прежний CI failure на другой ревизии.
- Прежняя проверка SP-08: 27 HTTP integration — passed; сейчас не повторялась. Mini App: 9 тестов и build — passed.
  HTTP использовал настоящий Python → Ktor → PostgreSQL; модели, Realtime/S3 и доставки — подставные.
  UI проверялся через подставной DOM, без визуальной приёмки.
- Запись доработана: свободное согласование времени через LLM/native tools, без второго согласия;
  отказ/неопределённость оставляет инициативу ученику. Успешная запись завершает знакомство.
  Сверка с [исходным UX-ТЗ](product/2026-11-13-first-acquaintance-ux.md): подтверждение отправляется перед
  завершающей репликой; в этом ответе нет My Progress. Unknown delivery подтверждения не запускает завершение.
  Подготовительный шаблон trial общий; модель получает цель, факты и историю для персонализации урока.
  Персональную подготовку пользователь отложил; точный текст приветствия оставлен по варианту 1.
  При unknown delivery согласованный приоритет — отсутствие дублей, даже если сообщение будет пропущено.
  Подтверждение — HTML-виджет с подписью и кнопками урока/календаря; перенос/отмена меняют то же сообщение.
  Экспорт Google/ICS читает актуальный урок после Telegram auth/ownership; нет OAuth или автосинхронизации.
  Trial получает сообщения в 08:00 по своему поясу, за час и в начале; совпадения и поздняя запись
  не создают лишних уведомлений. Durable receipts и версия урока защищают replay/перенос/отмену.
  До реальной приёмки не проверены native renderer виджета, Google Calendar и импорт ICS в Telegram iOS/Android.
  Попытка локального browser preview заблокирована `EATTESTATIONVERIFY`; JS/DOM проверки не заменяют визуальные.
- Пользователь поручил push, PR в `dev` и DEV redeploy ветки с V45–V46; merge не поручен.
  После поставки нужна живая Telegram-приёмка пауз, typing, прерывания и сброса по новому start.
  BotFather/config идентичности не менялись.

## Открытые ограничения и приёмка

- У приветствия отменяются только ещё не начатые отправки; внешний send после claim нельзя отозвать.
  Неизвестный результат не повторяется автоматически: возможен пропуск реплики. User typing недоступен;
  отмена опирается на сохранённое входящее сообщение. Контракт — в [tutor-chat](integrations/tutor-chat.md#первое-приветствие).

- Настоящий billing provider не реализован; коммерческие условия не выбраны. Mock не списывает деньги.
  Старый demo receipt не даёт entitlement.
- Старые успешные анализы не создают roadmap автоматически при deploy. Для одного ученика ранее
  выполнен разовый запуск: job succeeded, active revision 1, пять этапов и десять источников.
  Это частичное доказательство работы pipeline, не общий backfill или исправление старых данных.
- Fluency не имеет достоверной оценки. Confidence требует self-report; время и число уроков не доказывают освоение.
- Живая педагогическая приёмка диалога, regular, расписания, памяти и переноса навыков не доказана.
  Ранее пользователь положительно оценил разговор и объяснение маршрута; это не полная приёмка.
- Визуальная проверка My Progress и Telegram WebView открыта: прежние browser checks были заблокированы
  `EATTESTATIONVERIFY` и недоступным Computer Use host. Build не заменяет визуальную проверку.
- Нужны живые проверки пауз/barge-in, RU/EN, speaker/headphones, reconnect/end, сохранности контекста,
  reminders/follow-up и смены транспорта в 60-минутном уроке. Замер 1057 ms на семи ответах до смены VAD
  измерял commit → планирование playback, а не весь конец речи → слышимый звук.
- S3 backup/lifecycle и восстановление требуют отдельного подтверждения; наличие записанного аудио
  и 30-дневная очистка приложения не доказывают disaster recovery.

Платные прогоны после HTTP 402 не повторять без поручения. Порядок проверок и диагностики —
в [operations](integrations/operations.md). Реальные Telegram/model acceptance-прогоны не выполнялись.
