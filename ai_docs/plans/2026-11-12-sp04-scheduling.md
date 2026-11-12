# SP-04: расписание и общий контракт SP-03/SP-04

Контракт расписания, интегрированный с SP-03. Поставка и проверки — в [status.md](../status.md).

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

## Реализация и проверка

Native tools: save/get/cancel_schedule, get_lessons, create_extra_lesson,
reschedule/cancel_lesson и resolve_schedule_time. Text policy рекомендует семь занятий в неделю,
уважает меньшую частоту и не создаёт анкету. Read receipts не считаются мутациями при recovery.

При явном `reschedule_lesson` tool передаёт `launchMode=executable`: выбранный старый placeholder
становится реальным уроком с тем же ID. Остальные вхождения не активируются.
Регрессия now/after покрывает оба исходных launch_mode; это не массовая миграция placeholders.

Локальные сценарии: timezone/DST gap/fold и смена UTC-offset, повтор горизонта,
исключения отменённых/перенесённых встреч, изменение частоты/времени/длительности,
DST pending→resolved, reminder/no-show, unknown delivery, перенос во время генерации,
удаление/forget и конкурентный admission/cancel с regular preparation gate.
Хранилище и HTTP boundary настоящие; модели и Telegram подставные.

Естественность scheduling-диалога и доставка требуют отдельной живой приёмки.
Неопределённая DST-дата остаётся pending, без выдуманного UTC или штрафного missed.
Demo receipt не даёт entitlement; реальный admission использует [SP-05](2026-11-12-sp05-subscription.md).
Результаты и ограничения общей проверки — в [статусе](../status.md).
