# Состояние Speaky

Срез: 2026-11-12. Локальная реализация, DEV и живая приёмка — разные стадии.

## Реализовано локально

- SP-00: PostgreSQL foundation, общий профиль/память/ContextBuilder, Lesson/Call Session,
  scopes, epoch, версии, receipts и фоновые задачи. Legacy изолирован.
- SP-01: постоянный Telegram-диалог и native booking/memory tools.
- SP-02: отдельный Mini App, OpenAI Realtime/Marin через серверный WebSocket relay,
  trial, reconnect, playback/truncation, S3-архив и Telegram followup.
- SP-03: подготовка regular-плана моделью (цель, тема, стратегия), admission 15–60 минут
  по актуальному плану и завершённым прошлым анализам, Guided Conversation и устный feedback.
  Reconnect сохраняет план и исходный конец; следующая встреча читается из backend.
  Для длинного урока есть смена provider-сессии после 50 минут в безопасной паузе.
- SP-04: согласование расписания через LLM tools, реальные встречи на 14-дневном горизонте,
  разовые встречи, переносы/отмены, напоминания и неявки. DST требует уточнения без потери вхождения.
  V40 добавляет DST issues и версии уведомлений. Старые placeholders не активируются автоматически.
- Локальное исправление переноса: regular tools передают `now`/`after`, время вычисляет backend
  при сохранении; повтор receipt не сдвигает урок. Активный prompt отделён от legacy-ограничений
  и требует выполнять однозначный перенос без второго подтверждения. Исправление времени поставлено; активация выбранного старого placeholder при явном переносе также поставлена.
- SP-03 и SP-04 объединены в родительской ветке из отдельных worktree без текстовых конфликтов.
  Совместная локальная проверка PostgreSQL/lifecycle прошла; живая приёмка ещё не выполнена.

## SP-05: подписка с mock-провайдером

Локально добавлены Telegram payment card с условиями до оплаты, native subscription tools,
V41 checkout/events поверх Subscription/receipt/entitlement, успешный/неуспешный mock-платёж,
идемпотентность, автоматическое тестовое продление и отмена автопродления.
Regular admission требует активный entitlement; начатый оплаченный урок сохраняет reconnect
после истечения. Trial бесплатен; Schedule и будущие Lesson сохраняются.
Тариф обязателен в конфигурации, коммерческие условия пока не выбраны. Реальных списаний нет.
Локальные проверки SP-05: Python **631 passed**; после правки trial follow-up — ещё 25 целевых тестов.
Kotlin **282 unit + 53 PostgreSQL integration**, failures/errors/skipped = 0; fatJar и detekt прошли.
Mini App: 5 тестов, включая сборку; добавлен только текст отказа subscription_required, нового UI нет.
61 локальная ссылка и git diff --check прошли. Реальные Telegram/LLM и платёжная приёмка не запускались.
[Контракт и ограничения SP-05](plans/2026-11-12-sp05-subscription.md). На DEV не поставлен.

## Проверки объединённой версии

- Python: **628 passed**, одно dependency deprecation warning.
- Frontend: **5 passed**, syntax/build прошли.
- Kotlin: **281 unit + 46 PostgreSQL integration**, failures/errors/skipped = 0; fatJar и detekt прошли.
- Проверены schedule.agree → job claim/apply → regular admission → voice-scoped lesson.next,
  гонка admission/cancel (4 повтора) и подавление пустого regular followup без Telegram-отправки.
- Проверены 205 локальных ссылок в объединении; ссылки изменённых документов и git diff --check прошли.
- Для локального исправления now/after проверены задержка 35 секунд, неизменность времени при receipt replay,
  отклонение прошлого scheduled-времени и конфликтующих аргументов, отсутствие legacy-запрета в активном prompt.
  Прямой перенос проверен scripted model adapter в исходном ходе; живая LLM-проверка ещё не выполнена.
- Модели, Realtime, S3 и Telegram в локальных тестах подставные. Это не доказательство живого качества урока.

## DEV и ограничения

SP-03/SP-04 поставлены на DEV из зависимого [PR #53](https://github.com/greenblat17/1billion-dollar-startup/pull/53)
(base — ветка PR #52), runtime `c052dd9`: backend, AI и отдельный Mini App.
[Redeploy DEV 43255766656](https://github.com/greenblat17/1billion-dollar-startup/actions/runs/43255766656)
завершился success, включая общую readiness-проверку. Платные smoke-вызовы выключены.
Полного CI перед поставкой не ожидали по прямому поручению пользователя;
встроенные тесты и проверки deploy прошли. Исправлены стартовая гонка frontend CI и
установка PostgreSQL-клиента на обновлённом runner. Живой regular-урок ещё не проверен.
Пользователь оценил разговор как достаточно хороший для проверки полного trial.
Полная педагогическая приёмка SP-02, SP-03 и естественного scheduling-диалога SP-04 остаётся открытой.
Замер 1057 ms (7 ответов) относился к commit→планированию playback до изменения VAD;
он не включает всё ожидание конца речи и не доказывает фактическую слышимость.

SeaweedFS на DEV: приватный `speaky-trial-audio-dev`, сеть `speaky-storage`.
Запись аудио подтверждалась прежними звонками. Backup и S3 lifecycle ещё требуют проверки;
30-дневная очистка приложения сохранена. Архив-before-forward добавляет задержку в обе стороны.
Открыты живые проверки: полный trial/regular, RU/EN, паузы и перебивания, speaker/headphones,
reconnect, завершение, сохранность контекста/аудио, reminders/followup и 60-минутная смена транспорта.
Настоящий платёжный провайдер не реализован; SP-06 roadmap есть локально, но не поставлен; старый demo receipt не даёт entitlement.
PROD и merge main не выполнялись. Платные прогоны и реальные сообщения в интеграции SP-03/SP-04 не выполнялись.

[Архитектура](../architecture.md) · [Эксплуатация](integrations/operations.md) ·
[SP-03](plans/2026-11-12-sp03-regular-lessons.md) · [SP-04](plans/2026-11-12-sp04-scheduling.md)

## SP-06: roadmap и подготовка уроков

Локально добавлен model-backed `roadmap.prepare` поверх прежних Roadmap/revisions и job claim/apply.
Первый маршрут сразу используется после trial по уже согласованной цели — выбор пользователя.
Адаптация меняет тактику/порядок при новых наблюдениях из нескольких уроков; сохраняет outcomes.
Нет новых шкал, обещаний сроков уровня, критериев завершения этапов или экрана My Progress.

Planner SP03 использует roadmap и актуальную память в прежнем LessonPlan objective/topic/strategy.
Сохранены источники, версии, receipts, frozen plan, бесплатный trial и subscription admission SP05.
Исправление факта/цели и перенос отзывают устаревшую будущую подготовку. Text/Voice/Learning
получают общий контекст; устная следующая тема берётся только из готового плана реальной встречи.
Telegram объясняет сохранённый маршрут; trial follow-up ограниченно ждёт learning jobs и честно
обрабатывает недостаток данных или ошибку. V42 сохраняет результаты roadmap review.

Проверки SP-06: Python **638 passed**, затем 27 целевых после уточнения prompt;
Kotlin **282 unit + 55 PostgreSQL integration**, failures/errors/skipped = 0;
Python → HTTP Ktor → PG **10 passed**, включая четыре SP06 lifecycle сценария.
Mini App **5 passed**, syntax/build, fatJar и detekt прошли. 47 локальных ссылок и git diff --check прошли.
65-секундный compute прошёл с реальным renewal lease и одной попыткой. Проверены bound scope,
повторы receipts, stale/foreign sources, изменения цели/памяти, перенос во время compute,
frozen plan/reconnect, next topic/null, insufficient evidence, terminal failures и bounded follow-up.
PostgreSQL изолирован на loopback:56506; модели, голос и Telegram в проверках подставные.
Это не живая педагогическая приёмка. Единственное Python warning — dependency deprecation.
[Контракт и границы SP-06](plans/2026-11-12-sp06-roadmap.md).
База `c21ff97` совпадала с remote dev при старте и содержит SP05 #55.
Пользователь поручил PR в dev и Redeploy DEV из ветки SP-06. Поставка запускается; результат ещё не подтверждён.
Платные smoke-вызовы выключены; живая педагогическая приёмка не выполнена.
Для DEV нужны CI/readiness, V42 и отдельная диагностика старых failed jobs. Живая педагогическая приёмка остаётся открытой.

## Инцидент learning jobs

Strict JSON schemas и отсутствие общего default 500 токенов уже были в базовой dev.
Остаток подтверждён кодом: worker прерывал compute через 45 секунд. SP-06 локально добавляет
пределы AI/HTTP/worker 120/140/150 секунд и renewal lease/scope каждые 20 секунд.
Terminal failures теперь возвращают analysis_failed/roadmap_failed/plan_failed вместо бесконечного «позже».
DEV в этой задаче не проверялся: прежние failed jobs не восстановлены и могут продолжать блокировать admission.
Локальные исправления не означают устранение инцидента на DEV.
