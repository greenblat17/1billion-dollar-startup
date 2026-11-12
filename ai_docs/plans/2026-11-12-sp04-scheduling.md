# SP-04: расписание и общий контракт SP-03/SP-04

База: `0f93ee5506b540c6eb5d9a3cf150b19dfc377800`. Локальная реализация, без поставки.

## Общий технический контракт

Используются существующие `schedule`, `lesson`, `schedule_exception`, `call_session`,
`execution_scope`, `foundation_operation`, `trial_notice`. Нового агрегата урока нет.
Все команды проходят `/internal/foundation/v1`, envelope version=1,
operationId/action/args; повтор operationId с тем же input возвращает receipt,
с другим input — operation_conflict. Scope определяет student, роль и epoch.

- `schedule.agree`: sourceId/evidence, timezone (IANA), localTime (HH:mm),
  weekdays (ISO 1–7), durationMinutes (15–60), startDate (локальная первая дата),
  scheduleId/expectedVersion для изменения. Явное новое согласование создаёт executable
  regular Lesson на 14 дней с ежедневным продлением горизонта; старые placeholder без
  нового согласия не активируются. `trial.schedule` сохраняет прежнюю предварительную семантику.
- `trial.schedules`: schedules с id/version/agreement; `trial.schedule_cancel`:
  scheduleId/expectedVersion/sourceId/evidence. Изменение отменяет только будущие
  scheduled записи прежней версии, не текущие/завершённые встречи.
- `lesson.create`: kind=regular, launchMode=executable, startsAt (ISO UTC), timezone,
  durationMinutes, sourceId/evidence. Разовый урок только по явной просьбе.
- `lesson.list`: {} → lessons (свои, ближайшие scheduled/in_progress, максимум 40).
  `lesson.get`: lessonId → полный существующий Lesson с ownership.
  `lesson.next`: {} → lesson/null, ближайший будущий scheduled executable regular,
  исключая scope.lessonId. Не создаёт обещанную встречу при отсутствии записи.
- `lesson.reschedule`: lessonId/expectedVersion/timezone/sourceId/evidence и выбор времени:
  `timing=now`, `startsAt=null`, `delayMinutes=null` — время транзакции backend;
  `timing=after`, `startsAt=null`, `delayMinutes=1..525600` — минуты от времени транзакции;
  `timing=scheduled`, startsAt (ISO с offset), `delayMinutes=null` — согласованный instant.
  Эти же режимы поддерживает `lesson.create`; старые backend clients без timing используют scheduled.
  Trial clients с timing=now сохраняют совместимость. Receipt фиксирует время после первой записи,
  повтор не пересчитывает now/after. Прошедший scheduled instant по-прежнему отклоняется.
  Native tools regular требуют все три поля выбора, не вычисляют now по часам модели.
  `lesson.cancel`: lessonId/expectedVersion/sourceId/evidence. Только scheduled.
  Сохраняют occurrenceKey и exception, чтобы materializer не воскресил отмену/перенос.
- Admission остаётся `TrialAccess action=open`: существующий executable Lesson;
  startsAt <= now; первый вход до startsAt+duration. in_progress допускает reconnect
  по SP-02. Call Session уникален по attemptId; connected только один.
  План/снимок регулярного урока и preparation gate принадлежат SP-03.
- Завершение остаётся `voice.end` с текущим scope/Call Session, natural_goodbye
  завершает Lesson и ставит evaluation. Регулярный урок не создаёт trial followup.
  next встреча читается с backend, не выводится из периодичности.

Все изменения, admission, материализация и sweep берут lockStudent ДО чтения.
Отмена первой выигрывает → admission отклонён; admission первый → отмена отклонена
как invalid_state/version_conflict. После ожидания блокировки перечитать Lesson.
Schedule version и Lesson version независимы; optimistic version обязательна при изменениях.
Occurrence key = scheduleVersion:localDate, существующий UNIQUE(schedule_id,occurrence_key).
Материализация повторяема; cancelled/moved не заменяются. DST gap/fold не угадываются:
при согласовании возвращается `schedule_time_needs_clarification` с датой/временем/зоной
и инструкцией модели попросить другое время. Фоновая генерация сохраняет
`schedule_time_issue(status=pending)`, не создавая Lesson с выдуманным UTC.
Одно Telegram-уточнение использует delivery pending→unknown→sent; unknown не повторяется.
`trial.schedules.timeIssues` доступен модели; `schedule.resolve_time` принимает scheduleId,
expectedVersion, occurrenceKey, localTime, sourceId/evidence и создаёт Lesson именно этого
вхождения. Изменение/отмена расписания отменяет его нерешённые уточнения.
V40 также добавляет lesson_version к trial_notice; retention/delete учитывают новую таблицу.

Напоминание доступно за 15 минут, приглашение с начала до +5 минут, no_show после +5 минут.
В конце ни разу не начавшийся урок становится missed, без штрафа. in_progress не становится missed.
Дедупликация — Lesson/kind/version; перенос отзывает старую генерацию через fence/version.
Неизвестный результат отправки не повторяется в той же версии. Пустой AI reply подавляет notice.
Граница отправки — фиксация unknown до HTTP: уже начатый внешний запрос нельзя отозвать.
Подтверждённая отправка после удаления/forget не воссоздаёт историю ученика.

## Владение файлами

SP-04: TrialSchedule, LessonManagement, TrialWorker, новые scheduling helpers/tests,
FoundationService additions, ConversationRuns recovery, FoundationMaintenance deletion,
все общие миграции, foundation_tutor/trial_tools text tools,
этот план. SP-03: TrialAccess, TrialCommands, preparation/LearningState,
voice/context/Realtime/Mini App. trial_policy разделяется по блокам: TEXT_CAPABILITIES
у SP-04; SP-03 добавляет regular_lesson.py и правит trial_api/speaky_main. Общий status.md объединяет родитель.

## Проверки и границы

План: timezone/DST, повторная генерация, перенос/отмена, reminder dedup,
неявка и cancellation/admission race; Python tools, Kotlin и docs diff.
Demo receipt не даёт entitlement. Здесь нет настоящего платежа или проверки подписки.
Не включать legacy. Платные запросы и реальные Telegram отправки не разрешены.

## Реализация и интеграционный отчёт

Ветка `bb/sp-04-thr_kyizndwu3s`, отдельный worktree `thr_kyizndwu3s-1`.
Технический контракт согласован непосредственно с `thr_7a53d5zxxe` (SP-03).
Согласованные данные и пороги: backend остаётся источником истины; SP-03 не требует
добавочных миграций; начало/конец и reconnect используют прежний lifecycle.

Native tools доступны модели через TrialTutorModel: save/get/cancel_schedule,
get_lessons, create_extra_lesson, reschedule/cancel_lesson, resolve_schedule_time.
Text policy рекомендует 7 занятий, уважает меньшую частоту и не создаёт анкету.
Новые команды используют current source evidence, scope, версию и operation receipt.
Read receipts исключены из recovery мутаций; после сбоя подтверждается уже сохранённая
операция без повторного создания. Регулярный Lesson не получает подпись «пробный».

Интегрировать совместно с SP-03: его TrialAccess допускает regular по готовому плану,
TrialCommands различает окончания, trial_api умеет reminder/invitation и regular early exit,
regular_lesson/context и Mini App используют фактический Lesson. В этой ветке admission
regular намеренно не включён отдельно от SP-03. До объединения веток запускать новый
текстовый контракт в DEV нельзя. Ветка не поставлена.

Локальные проверки:
- Python: 595 passed; одно существующее dependency deprecation warning.
- Kotlin: 281 unit + 37 PostgreSQL integration; failures/errors/skipped: 0.
  `:server:buildFatJar detekt` и `git diff --check` прошли.
- Новые PostgreSQL проверки: DST gap/fold и смена UTC-offset, повтор горизонта,
  отменённые/перенесённые tombstones, изменение частоты/времени/длительности,
  DST pending→resolved и однократная отправка/удаление уточнения, reminder/no_show,
  неизвестная доставка, перенос во время генерации, конкурентные admission/cancel.
- Конкурентный admission/cancel использует общий существующий trial admission path;
  такую же гонку с regular preparation gate проверить после объединения SP-03/SP-04.
- Mock HTTP исключает реальные Telegram и модельные запросы. PostgreSQL отдельный,
  loopback 56484, тесты создают и удаляют изолированные схемы.
- Первый интеграционный запуск использовал занятый порт и получил ошибки подключения;
  исправлен адрес БД. Итоговые результаты относятся к повторному успешному прогону.

Ограничения: естественность диалога, настоящая доставка, WebView/голос и совместная
DEV-приёмка не проверены. Telegram exactly-once не обещается. Неразрешённая DST-дата
остаётся pending, не получает выдуманного времени или штрафного missed.
Настоящих payment/entitlement операций, legacy wiring, secrets и realtime transport
изменений здесь нет. Push/PR/deploy/merge не выполнялись.

### Предлагаемый текст для общего status.md

> SP-04 локально реализует согласование и изменение регулярного расписания через
> LLM native tools: дни, timezone, локальное время, 15–60 минут и первая дата.
> Backend создаёт реальные Lesson на 14-дневном горизонте, сохраняет разовые
> переносы/отмены, версии и receipts. DST требует явного уточнения; фоновое вхождение
> не теряется. Напоминания, приглашения и no-show защищены от повторов и устаревшей
> доставки. Demo-оплата не создаёт подписку. Работа regular voice/admission требует
> совместной интеграции SP-03; DEV и живая приёмка открыты.

### Предлагаемое дополнение architecture.md после интеграции

> TrialSchedule сохраняет единый Schedule: schedule.agree создаёт executable regular
> Lesson, trial.schedule оставляет прежний preliminary режим. TrialWorker продлевает
> горизонт под lockStudent, затем обслуживает versioned notices и lifecycle.
> V40 хранит schedule_time_issue для DST и lesson_version для уведомлений.
> LessonManagement исполняет text mutations; lesson.next возвращает backend-встречу
> для устного завершения. Read receipts не считаются сохранёнными мутациями диалога.

При явном `reschedule_lesson` tool передаёт `launchMode=executable`: выбранный старый placeholder становится реальным уроком с тем же ID. Остальные вхождения не активируются. DEV-логи 2026-11-12 подтвердили прежний отказ `not_permitted` в `LessonManagement.change`; регрессия покрывает now/after для обоих исходных launch_mode.
