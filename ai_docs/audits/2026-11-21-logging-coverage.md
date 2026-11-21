# Покрытие записи логов Speaky

Инвентаризация на 21 ноября 2026 года по исходному срезу `8e6a272d`. Требования к записи задаёт [стандарт](../plans/2026-11-21-runtime-logging.md).

Метка **Код** подтверждает текущую точку записи или намеренно тихий путь по исходникам. **Пробел** означает отсутствующую, неоднозначную или лишнюю запись. **Проверить** означает, что сценарий есть в карте и требует разбора его веток. **Вне поставки** означает, что текущая точка запуска Speaky этот путь не подключает. Указанная проверка описывает ожидаемый факт. Имена тестов дают начальные точки для проверки, включая тесты бизнес-поведения без проверки вывода.

Режимы обозначены в колонке «Что писать». **Событие** пишет владелец факта. **У владельца** использует запись другой границы. **Сводка** объединяет однотипные действия. **Тихо** оставляет штатную ветку без собственной строки.

## Точки запуска и регистрации

| Путь | Условие запуска | Регистрации в области аудита |
| --- | --- | --- |
| Ktor | `Application.main` вызывает `startSpeakyServer`. Webhook, TrialWorker и DEV harness зависят от конфигурации. | `/health`, `/telegram/webhook`, `/internal/foundation/v1`, `/internal/agent-test/v1/{id}`. `FoundationWorker` и `MockPaymentAdapter` запускаются из [SpeakyApplication.kt][ktor-root]. |
| Foundation | Ktor подключает основной реестр и отдельные route-файлы. | `commands`, `snapshot`, `operations/{id}`, `jobs`, `diagnostics`, `catalog`. Чтение `learning-state` и topics, plans и stages, tasks и scores, content, observations, problems, roadmap и priority. Trial access, events, audio, audio_cleanup, audio_deleted. [FoundationRoutes.kt][foundation-routes] · [каталог route-файлов][foundation] |
| Python | Docker вызывает фабрику `app.speaky_main:create_app`. | `/health`, `/internal/foundation/tutor/turn`, `/internal/foundation/compute`, `/internal/trial/followup`, `/internal/trial/initial-roadmap`, `/miniapp/api`, `/miniapp/diagnostics`, `/miniapp/voice`. [Dockerfile][ai-docker] · [speaky_main.py][python-root] · [foundation_api.py][python-foundation] · [trial_api.py][trial-api] |
| Mini App | nginx раздаёт статическую сборку. HTTPS edge направляет API и WS в Python. | `list`, `demo_pay`, `audio`, `progress`, `calendar`, `lesson_status` обслуживает `/miniapp/api`. Вход браузера начинается в [app.js][mini-app] · [nginx.conf][mini-nginx] · [edge][edge] |
| Команды и инструменты | Отдельные CLI, GitHub Actions и скрипты с хоста или машины разработчика. | Деплой, каталог, import, maintenance, backup, storage, monitoring, проверки и нагрузка. [infra][infra] · [workflows][workflows] · [Ktor CLI][foundation] · [load][load] |
| Альтернативные приложения | Явный запуск разработчиком или тестами. | FastAPI `app.main:create_app`, сохранённый Ktor wiring и CMP Android/iOS/Desktop. [Python legacy][python-legacy] · [Ktor legacy][ktor-legacy] · [CMP][cmp-prototype] |

## Матрица ситуаций

### Общий писатель

| Ситуация | Владелец и текущая запись | Что писать | Проверка | Статус |
| --- | --- | --- | --- | --- |
| Запись оформлена в общем формате | [RuntimeDiagnostics.kt][ktor-diag] и [diagnostics.py][python-diag] создают события с прежними маркерами. | Событие с явным единственным исходом, шаблоном сообщения и доверенным контекстом. | Ktor и Python writer tests сверяют обязательные поля схемы. Тест DEV сверил строки рабочей пары контейнеров. | PASS |
| Незарегистрированное значение или provider metadata конфликтуют с общими полями | [RuntimeDiagnostics.kt][ktor-diag] фильтрует метаданные до объединения. [diagnostics.py][python-diag] проверяет безопасные поля. | Проверить поля до записи. Оставить общие значения у владельца контракта. | `writerEmitsTheSharedEnvelopeAndRejectsProviderOverrides` и Python-тесты проверяют конфликт и вложенные секреты. | PASS |
| Первичная ошибка доходит до следующей границы | [RuntimeDiagnostics.kt][ktor-diag] и [diagnostics.py][python-diag] поддерживают безопасные поля и ID ошибки. | Событие у источника. Верхняя граница записывает собственный результат со ссылкой. | Проверены worker, HTTP-отказ на DEV и известная upstream-ссылка в Mini App. Для остальных путей проверить число первичных стеков. | Частично |
| Сбой formatter или записи | [RuntimeDiagnostics.kt][ktor-diag] и [diagnostics.py][python-diag] защищают вызывающую операцию. | Сводка потерь. Запрещён повторный вызов той же записи при ошибке писателя. | Ktor и Python тесты ломают запись и проверяют счётчик потерь. Для отказа диска и процесса нужен отдельный прогон. | Частично |
| Массовый повтор одной причины | [RuntimeDiagnostics.kt][ktor-diag] coalesce подавляет повторные отсрочки, [FoundationWorker][worker] считает продления. | Сводка только для разрешённых типов повторов. Пользовательская операция сохраняет свой результат. | Сравнили объём и память на повторных отсрочках и продлениях. Замеры горячего пути и диска открыты. | Частично |

### Запуск, Telegram и текстовый ход

| Ситуация | Владелец и текущая запись | Что писать | Проверка | Статус |
| --- | --- | --- | --- | --- |
| Ktor готов обслуживать запросы | [SpeakyApplication][ktor-root] пишет `backend_runtime` при событии `ServerReady`. | Событие готовности с режимом TLS, webhook и workers. | Собранный JAR подтвердил одно событие и `/health=200` без webhook. Нужна проверка TLS-режима. | Частично |
| БД, миграция, import marker или TLS блокируют запуск | [SpeakyApplication][ktor-root] пишет `backend_startup_failed` с этапом `tls` или `foundation`. | Событие с этапом и причиной до bind. | Из JAR проверены отсутствующий сертификат, отсутствующая отметка cutover и недоступная PostgreSQL. При отказе БД есть типы ошибок и нет строки готовности. Проверка отказа миграции открыта. | Частично |
| Worker включается только при валидном Mini App URL и токене | [SpeakyApplication][ktor-root] пишет `trial_worker_configuration` при запуске или пропуске. | Событие о конфигурационном пропуске без URL и токена. | Из JAR проверен `telegram_bot_unconfigured`. Проверки невалидного URL и штатного запуска открыты. | Частично |
| Остановка Ktor | [SpeakyApplication][ktor-root] пишет `backend_runtime` после запроса отмены workers и закрытия HTTP-клиента. | Событие остановки, исходы длинных операций у их владельцев. | В рабочем JAR при SIGTERM получена одна строка `shutdown`. Операции в полёте требуют отдельных тестов. | Частично |
| Python стартует или не может настроить Foundation | [speaky_main.py][python-root] пишет `ai_runtime` при запуске, завершении и известных отказах конфигурации. | Событие об итоговой готовности или этапе отказа. | `test_speaky_runtime.py` подтверждает `startup` и `shutdown`. Отказы конфигурации проверяются отдельно. | Частично |
| Успешный health probe | [Python][python-root] пропускает `/health`; [nginx][mini-nginx] отключает access log. [Ktor][ktor-root] отвечает без отдельного события. | Тихо. Отказ фиксирует проверяющий процесс. | Серия успешных probes, проверить отсутствие отдельной строки. | Код |
| Webhook регистрируется или получает отказ Bot API | [TelegramWebhook][webhook] выполняет регистрацию. | Событие регистрации с безопасным кодом отказа. | Статусы Bot API, таймаут и ошибка TLS. | Проверить |
| Webhook отклонён до обработки update | [TelegramWebhook][webhook] проверяет секрет и разбирает update. | Событие отказа с отдельными кодами auth и формата. | Неверный секрет и невалидный JSON. | Проверить |
| Голосовой, групповой или неподдерживаемый Telegram update пропущен | [TelegramWebhook][webhook] выбирает обработчик. | Сводка штатных пропусков, без текста и ID чата. | Неподдерживаемый update, голос и автоматический повтор. | Проверить |
| Private-text записан и подтверждён | [TelegramTextIntake][intake] владеет durable входом. | Событие после commit, отдельное от итога хода Telegram. | `FoundationIntegrationTest`, сравнить ACK и receipt. | Проверить |
| Повтор Telegram update | [TelegramTextIntake][intake] использует сохранённое состояние. | Событие replay без повторного эффекта. | Дважды подать один update, сверить строки и inbox. | Проверить |
| Ошибка записи или диспетчеризации inbox | [TelegramTextIntake][intake] пишет `telegram_text_intake_dispatch_failed`. | Событие с различием «вход не сохранён» и «вход ждёт worker». | Остановить БД до commit и остановить диспетчер после commit. | Проверить |
| Новый `/start` сбросил профиль и создал новую идентичность | [Foundation][foundation] и [TelegramTextIntake][intake] хранят границу команды. | Событие сброса после commit. | `FoundationIntegrationTest`, проверить новый epoch и один сброс. | Проверить |
| Replay `/start` использует сохранённую границу | [TelegramTextIntake][intake] восстанавливает ход. | Событие replay, без нового сброса. | Повторить доставку одной команды. | Проверить |
| Новый вход отменил оставшиеся реплики знакомства | [FoundationChat][chat] работает с доставками и receipts. | Событие superseded с сохранением подтверждённых отправок. | `TelegramIntroductionWaitTest`, вход до и после send claim. | Проверить |
| Run занят, lease истёк, устаревший исполнитель завершает ход | [ConversationRuns][runs] проверяет lease и fence. | Событие о потере или перехвате lease, обычный busy агрегировать. | Два исполнителя, просроченный fence и перезапуск. | Проверить |
| AI вычислил ответ, Backend сохранил результат хода | [TutorClient][tutor-client] пишет `tutor_http`. [FoundationChat][chat] владеет состоянием хода. | Событие compute у адаптера и отдельный факт commit у Foundation. | Успешный HTTP при rollback записи результата. | Проверить |
| AI недоступен, отклонил вызов или вернул неверный ответ | [TutorClient][tutor-client] пишет `tutor_http_failed` и `tutor_failed`. | Событие попытки, ссылку на первичную ошибку передавать дальше. | TransportError, HTTP отказ и невалидный JSON с одинаковым trace. | Проверить |
| Telegram подтвердил исходящую реплику, карточку или шаг знакомства | [FoundationChat][chat] хранит `sent`. | Событие подтверждения у отправителя, commit receipt у Foundation. | `TutorBookingDeliveryTest`, сверить каждую часть. | Проверить |
| Telegram отклонил отправку или запрос оборвался | [SpeakyTelegram][telegram] и [FoundationChat][chat] обрабатывают сбой и receipts. | Событие с различимыми rejected и unknown. Не обещать повтор неизвестной доставки. | HTTP 400, 429, transport timeout, отказ commit receipt. | Проверить |

### Foundation, учебные данные и доступ

| Ситуация | Владелец и текущая запись | Что писать | Проверка | Статус |
| --- | --- | --- | --- | --- |
| Успешное чтение snapshot, учебного состояния, каталога или receipt | [FoundationRoutes][foundation-routes] пишет `http_request` для общего обработчика. | Событие результата чтения с именем операции. | Сверить registrations и фактический маршрут в выводе. | Код |
| Авторизация, scope, ownership или входной контракт отклонены | [FoundationRoutes][foundation-routes] пишет `foundation_failed`. | Событие с безопасной причиной и статусом у границы. | `FoundationIntegrationTest`, denied для неверного токена, scope и владельца. | Код |
| PostgreSQL отказал в команде | [FoundationService][service] пишет `backend_command_failed`. [FoundationRoutes][foundation-routes] пишет `foundation_failed`. | Событие первичной ошибки и связанный исход HTTP. Проверить, что ID и стек не дублируются. | `RuntimeDiagnosticsTest` проверяет ID и стек. Дополнить сквозным запросом. | Проверить |
| Старая версия, конфликт или истёкший receipt | [FoundationService][service] и [FoundationRoutes][foundation-routes] записывают общий отказ. | Событие с точным кодом конфликта без аргументов. | `FoundationIntegrationTest`, разные конфликтующие записи. | Проверить |
| Новая команда и replay возвращают тот же payload | [FoundationService][service] пишет `backend_command` с `completed` или `replayed`. | Событие replay или исход новой команды после commit. | `assessmentWritesEvidenceBoundStateWithoutChangingSelfReport` проверяет одно сохранение ревизии и отдельную строку replay. | PASS для `job.apply` |
| Валидная команда отсутствует в реестре событий | [FoundationService][service] использует `diagnosticActions` для зарегистрированных команд. | Событие с зарегистрированным именем операции. | По исходникам у 80 веток `dispatch` есть диагностическое имя. | PASS по коду |
| Обновление профиля, цели, контекста или обращения | [FoundationService][service] делегирует `tutor.update_context`, `learning.update`, `onboarding.update`, `student.preferred_address`. | Событие об изменении либо пропуске после commit, без значений полей. | `StudentLearningIntegrationTest`, новая ревизия, no-op и конфликт. | Проверить |
| Memory propose/search/discuss/record/review | [FoundationService][service] и операции [Foundation][foundation] различают кандидата, факт и чтение. | Событие результата взамен вывода аргументов. | Pending candidate и подтверждённый факт дают разные безопасные результаты. | Проверить |
| Memory confirm/reject/forget, скрытие данных и отзыв scopes | [Foundation][foundation] выполняет операции записи. | Событие фактического изменения, отдельные отказ и повтор. | Свежий и просроченный источник, повторный forget и отзыв scope. | Проверить |
| `student.delete`, `learning.forget`, `deletion.replay` | [FoundationService][service] делегирует maintenance. | Событие результата удаления или восстановления ledger после commit, без удалённых данных. | Прерванная транзакция, повтор и ранее удалённые данные. | Проверить |
| Lesson create/list/get/next, запись/отмена/перенос | [FoundationService][service] делегирует LessonManagement и Trial. | Для чтения итог у границы. Для записи различить новую встречу, replay, конфликт и отказ. | `TrialIntegrationTest`, slot занят, перенос и повтор запроса. | Проверить |
| Открытие или повторный вход в урок, отказ в admission | [TrialRoutes][trial-routes] и [TrialAccess][trial-access] выбирают доступ. | Событие admission с кодом причины. Клиентский WS пишет собственный итог. | `TrialIntegrationTest`, занятой episode, окно reconnect, чужой урок и отсутствующий план. | Проверить |
| Trial phase, preferences, dialogue, reaction и ready | [FoundationService][service] и [Foundation][foundation] владеют переходами. | Событие принятого или отклонённого перехода с причиной. | `TrialSp24Test`, ранний выход, повтор события, устаревшая фаза. | Проверить |
| Schedule create/agree/materialize и разрешение времени | [FoundationService][service] делегирует расписание. | Событие изменения после commit. Неоднозначное местное время имеет отдельную причину. | Валидный слот, timezone, DST gap и повтор materialization. | Проверить |
| Подписка, demo checkout, cancel renewal и mock callback | [Foundation][foundation] и [MockBillingTelegram][billing] обрабатывают состояние платежа. | Событие принятия или отказа с причиной, отдельно от записи entitlement. | Дублированный callback, неправильная сумма, demo без реальной оплаты. | Проверить |
| Mock worker применил или пропустил renewal | [MockPaymentAdapter][mock-payment] пишет `billing_worker_failed` при исключении. | Событие фактического результата. Пустой опрос тихий. | Просроченный entitlement, отключённый mock и отказ БД. | Проверить |
| Learning Tasks create/update/progress/transition | [FoundationService][service] делегирует LearningTasks. | Событие изменения статуса или ревизии. | `LearningTasksIntegrationTest`, допустимый и запрещённый переход, устаревший input. | Проверить |
| Learning Plan create/revise/select-stage | [FoundationService][service] делегирует LearningPlans. | Событие сохранённой ревизии или выбора стадии. | `LearningPlansIntegrationTest`, no-op и конфликт версии. | Проверить |
| Learning Content create/revise/issue/mark-defective, action request | [FoundationService][service] делегирует выдачу, замену и отметку дефекта. | Событие фактической выдачи, замены или блокировки. | `LearningContentIntegrationTest`, плохая версия, повтор, замена дефекта. | Проверить |
| Observation record/revise/invalidate, topic record, Score и Problems | [FoundationService][service] пишет общий исход команды. | Событие записи observation. Изменившиеся score, problem и evidence указать в итоговой сводке. | `LearningObservationScoreIntegrationTest`, повторный расчёт и инвалидирование. | Проверить |
| Roadmap save/revise/priority/problem decide | [FoundationService][service] пишет общий исход команды. | Событие новой ревизии или конкретного пропуска. | `PersonalizedRoadmapIntegrationTest`, `RoadmapPriorityIntegrationTest`. | Проверить |
| Настройка operator/DEV scope и проверка harness | [AgentTestRoutes][agent-routes] доступны при `dev` и `AGENT_TEST_ENABLED=true`. | Событие допуска, запрета или подтверждённого fixture-эффекта. | Пары dev/prod и enable/disable, неверный token и чужой ученик. | Проверить |

Имена из `dispatch`, отсутствующие в `diagnosticActions` на исходном срезе: `access.confirm_payment`, `learning.content.mark-defective`, `learning.plan.select-stage`, `lesson.end_episode`, `memory.forget_requested`, `memory.reject_requested`, `schedule.resolve_time`, `subscription.cancel_renewal`, `trial.schedule_cancel`, `tutor.update_context`. [FoundationService.kt][service]

### Фоновые задачи и доставка

[Реестр `jobKinds`][jobs] содержит 13 видов. [Worker][worker] запускает вычисление и применение. [LearningEngine][learning] вычисляет результаты Python-веток. Для `lesson.review` пустой результат Python штатный. Данные Review собирает Ktor при применении. Для `retention.prune` вычисление внешней моделью не требуется. `schedule.materialize` и `notification.reminder` зарегистрированы. Shipping root оставляет `businessHandlers` пустым, поэтому их compute возвращает `handler_unavailable`. У TrialWorker отдельное расписание и outbox.

| Ситуация | Владелец и текущая запись | Что писать | Проверка | Статус |
| --- | --- | --- | --- | --- |
| Новая работа создана или уже существует | [FoundationJobs][jobs] сохраняет задачу и использует ключ дедупликации. | Событие создания или replay после commit с ID источника. | Запустить enqueue повторно и откатить одну транзакцию. | Проверить |
| Claim получил задачу | [FoundationWorker][worker] пишет `foundation_job` на стадии `claim`. | Событие с job ID, видом, fence и номером попытки. | Запустить два worker и сверить ровно один claim. | Код |
| Настоящий пустой polling | [FoundationWorker][worker] пропускает `job=null`. | Тихо. | `committedQueueTransitionsAreVisibleAndEmptyPollsAndRollbackAreQuiet` подтверждает отсутствие события. | PASS |
| Claim перевёл просроченную задачу или попытки исчерпаны | [FoundationJobs][jobs] агрегирует завершившиеся задачи в `foundation_job_claim_decision` после commit. | Событие или ограниченная сводка после commit с причиной перехода. | `committedQueueTransitionsAreVisibleAndEmptyPollsAndRollbackAreQuiet` проверяет дедлайн. Истечение последней аренды открыто. | Частично |
| Claim признал работу устаревшей | [FoundationJobs][jobs] обновляет состояние до возврата. | Событие superseded после commit с причиной. | Работу заменил свежий источник, новый claim отсутствует. | Пробел |
| Claim отложил `lesson.prepare` | [FoundationJobs][jobs] изменяет `due_at` и возвращает `job=null`, когда зависящие вычисления не готовы. | Событие или сводка отложенных задач с кодом причины после commit. | Подготовить незаконченный анализ, Roadmap или приоритет. Отличить от пустой очереди. | Пробел |
| Claim заблокировал повтор напоминания с неизвестной доставкой | [FoundationJobs][jobs] сохраняет `delivery_unknown` и возвращает `job=null`. | Событие отказа с job ID после commit. | `reminderContractSkipsCancelledLessonsAndNeverResendsUnknownDelivery` проверяет одну запись `delivery_unknown` и повторный пустой опрос. | PASS |
| Lease продлена | [FoundationWorker][worker] пишет `foundation_job` на стадии `renew`. Внешняя `job.renew` также входит в общий журнал команд. | Сводка продлений в итоге попытки. | Длинный compute с несколькими heartbeat и внешний renew. | Пробел |
| Lease потеряна | [FoundationWorker][worker] обрабатывает потерю lease и отмену compute. | Событие немедленно, со ссылкой на попытку и причину. | Подменить fence при compute. | Проверить |
| Compute завершён, отклонён или просрочен | [FoundationWorker][worker] пишет `foundation_job_ai_call`, [foundation_api.py][python-foundation] пишет `foundation_compute` и `foundation_failed`. | Событие у HTTP-адаптера и факт вычисления у Python с теми же job ID и fence. | Успех, таймаут, неизвестная версия, неверная схема и отмена. | Пробел |
| Apply завершён или вытеснен новой версией | [FoundationWorker][worker] пишет `job_status` из receipt. Писатель выводит общий `outcome` из события. | Событие фактического исхода из receipt после commit. | Один `superseded` и один `succeeded`, rollback без успешного события. | Пробел |
| Retry запланирован или работа окончательно остановлена | [FoundationWorker][worker] пишет `foundation_job_failed` и `failure_persisted`. | Событие решения retry/terminal с номером попытки и безопасной причиной. | `workerLinksRetryAndTerminalDecisionsToTheirOwnPrimaryFailure` проверяет повтор и конечный отказ. Отмена и ошибка записи receipt открыты. | Частично |
| `trial.observe` создал наблюдения или пропустил непригодные источники | [FoundationJobs][jobs] применяет результат экстрактора. | Сводка сохранённых и пропущенных источников без реплик. | `test_trial_observations.py`, пустой, истёкший и изменённый источник. | Проверить |
| `trial.assess` записал Assessment | [TrialAssessment][assessment] возвращает результат применения worker. | Событие Assessment с ревизией после commit. `Unknown` уровень сохраняется как штатные данные. | `test_trial_assessment.py`, валидные observations и малая выборка. | Проверить |
| Assessment вытеснен изменением источника или уровня | [TrialAssessment][assessment] возвращает `false` при проверках актуальности. | Событие superseded с наблюдаемой причиной. | По отдельности изменить transcript, каталог, learning revision и уровень. | Пробел |
| Assessment сохранён, генерация Roadmap пропущена | [TrialAssessment][assessment] пишет `trial_roadmap_decision` после commit. | Событие решения после commit. Причины различают отсутствующую цель, уровень и оба поля. | `assessmentLogsWhyRoadmapWasSkippedWithoutEnqueueing` проверяет три причины, ровно одну строку на исход и отсутствие задачи Roadmap. Откат после решения проверяется отдельно. | Частично |
| Assessment поставил `roadmap.generate` | [TrialAssessment][assessment] вызывает enqueue при наличии обязательных данных. | Событие создания с ID Assessment и новой задачи. | Повтор задачи и запуск с актуальным результатом. | Проверить |
| `roadmap.prepare` или `roadmap.generate` сохранил версию | [FoundationJobs][jobs] делегирует RoadmapPreparation и PersonalizedRoadmap. | Событие сохранения или вытеснения с версией. | `PersonalizedRoadmapIntegrationTest`, устаревший Assessment при apply. | Проверить |
| `roadmap.prioritize` пересчитал или сохранил прежний результат | [FoundationJobs][jobs] делегирует приоритеты. | Событие результата и причина no-op или superseded. | `RoadmapPriorityIntegrationTest`, неизменённые и сменившиеся входы. | Проверить |
| `lesson.prepare` записал план или пропустил устаревший | [FoundationJobs][jobs] применяет план. | Событие новой ревизии или точной причины пропуска. | `test_regular_lesson.py`, завершённый и недостающий анализ. | Проверить |
| `lesson.content.prepare` подготовил действие или получил невалидный материал | [LearningEngine][learning] передаёт результат [FoundationJobs][jobs]. | Событие сохранения у Ktor, отказ генерации/проверки у Python. | `test_learning_content_generation.py`, ошибка схемы и смысловой проверки. | Проверить |
| `lesson.evaluate` применил или отклонил анализ | [FoundationJobs][jobs] валидирует анализ и источник. | Событие после commit или факт невалидного результата. | Изменить версию урока между compute и apply. | Проверить |
| `lesson.review` записал Review или пропустил старую ревизию | [PostLessonReview][review] формирует данные и применяет результат. | Событие commit либо superseded. Наблюдение чтения snapshot отдельно. | `PostLessonReviewIntegrationTest`, устаревший источник и повтор работы. | Проверить |
| `memory.extract` нашёл кандидатов или источник истёк | [LearningEngine][learning] и [FoundationJobs][jobs] отвечают за compute и apply. | Сводка кандидатов без фактов, причина пропуска у владельца решения. | `test_memory_jobs.py`, нулевой и ненулевой результат, просроченный источник. | Проверить |
| `schedule.materialize` получил `handler_unavailable` | [BackgroundContracts][background] применяет результат только при явно переданном handler. [FoundationWorker][worker] пишет `foundation_job_failed` без него. | Событие отказа в shipping root. При отдельной конфигурации сводка созданных слотов после commit. | Запросить задачу в shipping wiring и повторить с test handler. | Код |
| `notification.reminder` получил `handler_unavailable` | [FoundationWorker][worker] пишет `foundation_job_failed`. Отказ возвращает запись `reminder_delivery` в `not_sent`. | Событие отказа и факт сохранённого `not_sent`. | Запросить задачу без handler, проверить исход receipt. | Проверить |
| `retention.prune` удалил истёкшие строки | [FoundationJobs][jobs] передаёт работу maintenance. | Сводка по типам удалённых данных, без содержимого. | Нет истёкших данных, частичный набор, rollback. | Проверить |
| Roadmap delivery подготовлена, уже готова или перенесена на retry | [InitialRoadmapDelivery][delivery] пишет `initial_roadmap_delivery` на `prepare_persist` и `prepare_retry`. | Событие готовности или повтора после commit. | Тесты PostgreSQL проверяют сохранение сообщения, повтор после отказа AI и `superseded` у потерявшей аренду попытки. | PASS для проверенных исходов |
| Roadmap отправлена полностью или по частям | [InitialRoadmapDelivery][delivery] пишет `telegram_request` и `delivery_persist`. | Событие каждой значимой попытки и общая запись исхода доставки. | Первая часть подтверждена, вторая отказана. | Проверить |
| Roadmap send получил явный отказ Telegram | [InitialRoadmapDelivery][delivery] пишет `initial_roadmap_delivery_unknown` для неуспешного ответа. | Событие rejected при доказанном отказе. | `completedUnpaidTrialSendsSavedRoadmapOnceAndKeepsUnknownReceipt` проверяет одну запись `rejected`, статус БД `unknown` и отсутствие повторной отправки. | PASS |
| Roadmap send оборвался до подтверждения | [InitialRoadmapDelivery][delivery] пишет `initial_roadmap_delivery_unknown` на стадии запроса. | Событие unknown с delivery ID, частью и попыткой. | Таймаут после отправки запроса, отсутствие нового send. | Код |
| Trial notice отменён до отправки | TrialAccess пишет отмену при открытии звонка. [TrialWorker][trial-worker] пишет отказ от отправки с тем же trace. | Событие отмены после commit. Причина принадлежит коду, который её наблюдал. | `reconnectDuringFollowupGenerationSuppressesTelegramDelivery` подтверждает две связанные строки и отсутствие вызова Telegram. Другие причины отмены требуют отдельных тестов. | Частично |
| Напоминание или followup принят, отклонён либо имеет неизвестную доставку | [TrialWorker][trial-worker] пишет claim, send и persist. Неуспешный HTTP оставляет unknown. | Событие с отличием явного отказа от отсутствия подтверждения. | `unknownTelegramDeliveryIsNeverAutomaticallyRepeated` проверяет HTTP 502, одну строку unknown и отсутствие повторной отправки. Явный HTTP 400 и таймаут остаются открыты. | Частично |

### Python и голос

| Ситуация | Владелец и текущая запись | Что писать | Проверка | Статус |
| --- | --- | --- | --- | --- |
| Вход Python авторизован или отклонён | [speaky_main.py][python-root] пишет `http_request`, `http_failed`. | Событие результата с маршрутом, статусом и безопасной причиной. | `test_speaky_runtime.py`, denied, 404, oversized и отмена. | Код |
| Snapshot загружен или backend вернул ошибку | [FoundationClient][client] пишет `backend_request` и `backend_failed`. | Событие попытки с trace и upstream error ID. | `test_runtime_diagnostics.py`, transport и malformed JSON. | Код |
| Запись Foundation оборвалась или восстановлена чтением receipt | [FoundationClient][client] различает transport outcome. | Событие неизвестного исхода и отдельный итог reconciliation. | Mock timeout до и после commit. | Проверить |
| Отбор памяти упал, ContextBuilder продолжил | [ContextBuilder][context] пишет `memory_selection_failed`. | Событие деградации с продолжением на доступном контексте. | Сбой селектора при рабочем snapshot, ошибка snapshot отдельно. | Код |
| Контекст усечён или пропустил истёкшие источники | [ContextBuilder][context] ограничивает проекции. | Сводка причин и количеств на вычисление, без содержания. | Переполнение бюджета, пустая проекция и истёкшая история. | Проверить |
| Native tool принят, отклонён, завершён или пропущен | [FoundationTutor][tutor] вызывает [tutor_diagnostics.py][tutor-diag]. | Событие попытки в `tutor_tool`, без аргументов. | `test_tutor_diagnostics.py`, неверные args, backend отказ, повтор и успех. | Код |
| Модель отказала, нарушила контракт или вызвана повторно | [provider_call_log.py][provider] пишет `provider_call` и `provider_failed`. | Событие попытки у provider adapter. Отдельно писать решение retry или fallback. | `test_provider_call_log.py`, 400/402/429, timeout, invalid JSON/schema. | Проверить |
| Внутренний followup или initial Roadmap подготовил сообщение | [trial_api.py][trial-api] обслуживает отдельные авторизованные вызовы worker. | Событие результата подготовки с source ID, без готового текста. Отправку пишет Ktor. | Изменённый источник, отказ auth, ошибка генерации, непригодная Roadmap. | Проверить |
| Browser API list/progress/lesson_status вернул данные | [trial_api.py][trial-api] передаёт запрос Foundation. | У владельца HTTP и доступа. Свой итог UI при существенном переходе. | Успех, auth, ошибка upstream и ошибка JSON на клиенте. | Проверить |
| Browser API calendar экспортировал данные либо не нашёл урок | [trial_api.py][trial-api] вызывает Calendar export после доступа. | Событие результата экспорта или причины отказа. | Чужой, просроченный и валидный урок, ошибка генерации. | Проверить |
| Browser API audio создал подписанные ссылки или отказал | [trial_api.py][trial-api] и [trial_audio.py][audio] владеют выдачей. | Событие стадии доступа или подписи, без S3 ключа и URL. | Невалидный урок, отсутствующий storage, ошибка подписи. | Проверить |
| Client diagnostics приняты или отклонены | [trial_api.py][trial-api] принимает ограниченный отчёт после авторизации. | У владельца server ingress. Секреты и произвольный текст исключены. | `test_runtime_diagnostics.py` проверяет авторизацию, безопасные коды, отсутствие свободного текста и отказ 413 до обращения к Foundation. | PASS для проверенных исходов |
| WS открылся до первой команды | [trial_api.py][trial-api] сохраняет серверный trace и отдельный client attempt ID. | Событие с одним server trace на соединение. Клиентский attempt ID отдельный. | `test_voice_socket_uses_one_server_trace_through_rejection` проверяет по две строки на соединение и разные серверные trace при одном client attempt ID. | PASS для проверенных исходов |
| WS отклонил вход до admission | [trial_api.py][trial-api] пишет `trial_failed` и `agent_voice_denied`. | Событие с причиной конфигурации, auth, протокола, размера или дедлайна первого кадра. | Все ветки раннего выхода, DEV grant и смешанные credentials. | Проверить |
| WS admission успешен | [trial_api.py][trial-api] пишет `trial_socket` на стадии `admission`. | Событие с lesson и episode ID. | `test_trial_voice.py`, новый и повторный вход. | Код |
| Realtime не открылся, потерял связь или остановлен | [connection.py][realtime-connection] пишет `trial_session_failed`. | Событие причины и этапа с ID провайдера при наличии. | `test_trial_realtime.py`, handshake timeout, отказ API и cancellation. | Проверить |
| Realtime получил первый аудио-результат, транскрипцию или статус ответа | [realtime_voice][realtime] пишет `trial_realtime_first_audio`, `trial_realtime_transcription`, `trial_realtime_response`. | Событие перехода со стадией, duration и статусом, без текста и аудио. | `test_trial_realtime.py`, аудио, пустой ответ и отмена. | Код |
| Barge-in, проигрывание, mute, статусы клиента | [session.py][realtime-session] пишет `trial_interrupt`, `trial_mute`, `trial_client_metric`. | Событие перехода. Сводка кадров и playback, факт слышимости неизвестен. | Прерывание, остановка и частичное воспроизведение. | Проверить |
| Trial SP-24 выбрал, сохранил или пропустил этап | [session.py][realtime-session] и [TrialSp24][foundation] управляют этапами. | Событие перехода, replay и раннего выхода. | `test_trial_sp24.py`, без повтора подтверждённого предложения на reconnect. | Проверить |
| Regular action выдан, заменён, закончен или заблокирован | [session.py][realtime-session] пишет `regular_action_replacement_failed`. | Событие выдачи, замены и точного отказа, остальные факты у Foundation. | `test_regular_teaching.py`, запрос при готовом и дефектном материале. | Проверить |
| Observation сохранён, восстановлен или пропущен | [session.py][realtime-session] пишет `learning_observation_written`, `learning_observation_recovery`, `learning_observation_analysis_skipped`. | Событие записи или пропуска при существенном изменении. | `test_regular_evidence.py`, timeout записи, восстановление и неподходящая реплика. | Код |
| Архив аудио сохранил порцию | [FoundationService][service] подавляет успешные `voice.audio`, `voice.audio_stored`, `voice.heartbeat`, `voice.playback`. | Сводка по соединению, без строки на порцию или heartbeat. | `test_audio_batch_success_is_quiet_and_failure_keeps_correlation` проверяет тихий успех. Ktor-тест reconnect проверяет 30 heartbeat без событий. | PASS для проверенных исходов |
| Архив аудио, фиксация metadata или cleanup упали | [trial_audio.py][audio] и [realtime_voice][realtime] пишут ошибки хранения и cleanup. | Событие каждой различной причины. Итог cleanup связывать с исходным сбоем. | Поломка upload, записи metadata и cleanup во время одного сеанса. | Проверить |
| Соединение завершено пользователем, таймаутом или разрывом | [trial_api.py][trial-api] и [session.py][realtime-session] пишут `trial_disconnected`, `trial_session`, `trial_cleanup_failed`. | Событие исхода соединения и сводка; завершение урока подтверждает Foundation. | Нормальный end, разрыв, reconnect, сбой finish и отказ cleanup. | Проверить |

### Mini App и прокси

| Ситуация | Владелец и текущая запись | Что писать | Проверка | Статус |
| --- | --- | --- | --- | --- |
| Страница или assets недоступны до запуска JavaScript | [nginx.conf][mini-nginx] и [edge][edge] обслуживают страницу и upstream. | Событие прокси с безопасным route/status. Успех assets можно агрегировать. | 404 asset, 502 upstream и отказ WS upgrade. | Проверить |
| Вход с неподходящей ссылкой или без Telegram initData | [app.js][mini-app] переключает состояния входа. | Событие причины при доступном авторизованном канале. Остальные случаи видны у HTTP ingress при наличии запроса. | `lesson-entry.test.mjs`, отсутствие Telegram, невалидная ссылка. | Проверить |
| My Progress загрузил и показал данные | [progress.js][mini-progress] отображает progress, empty и loading. | Сводка пользовательского исхода. Пустой прогресс штатный. | `progress.test.mjs`, пустой и загруженный UI. | Проверить |
| My Progress получил auth отказ | [progress.js][mini-progress] отображает состояние auth. | У владельца auth. Связать собственный результат UI с известным upstream error ID. | Устаревший initData и серверный error ID. | Пробел |
| My Progress не получил ответ, не разобрал JSON или не отрисовал данные | [progress.js][mini-progress] передаёт стадию через ограниченный адаптер диагностики. | Событие с разными стадиями transport, decode и render. | `progress.test.mjs` проверяет различимые стадии разбора, рендера и отказа HTTP. | PASS для проверенных исходов |
| Открыт список встреч, список пуст или запрос не удался | [schedule.js][mini-schedule] рисует встречи и вызывает `reportDiagnostic` для части отказов. | Событие отличимого отказа с кодом из реестра. Успехи можно сводить. | `meetings.test.mjs`, ошибочный JSON и сеть без произвольного `error.message`. | Пробел |
| Demo pay подтверждён или кнопка возвращена к retry | [schedule.js][mini-schedule] меняет UI. Факт оплаты принадлежит Foundation. | У владельца платежа. Отдельное событие при клиентском сбое запроса или отображения. | Успех, отказ и повтор клика. | Пробел |
| Calendar скачан, открыт share или приложение выбрало fallback | [schedule.js][mini-schedule] вызывает браузерные API. | Событие выбора fallback и штатной отмены share. Успех получения файла виден у API. | `calendar.test.mjs`, share, AbortError, отказ share и ошибка скачивания. | Пробел |
| Вход в звонок отложен или выбран режим reconnect | [call.js][mini-call] управляет lobby, таймером и поколением подключения. | Событие существенного исхода попытки и отдельно новое подключение. | `lesson-entry.test.mjs`, ожидание, повтор и сброс таймера при смене экрана. | Проверить |
| Микрофон запрещён, отсутствует или занят | [call.js][mini-call] передаёт тип в `reportDiagnostic`. | Событие стадии microphone при доступном HTTP. | `microphone.test.mjs`, сервер принимает коды без raw error. | Код |
| Не запустился AudioContext или AudioWorklet | [call.js][mini-call] передаёт стадии `audio_context` и `worklet`. | Событие конкретной стадии, отдельно успешный ручной resume. | Ошибка `addModule` и неудачное восстановление контекста. | Проверить |
| WS закрылся, оборвался или переполнился буфер | [call.js][mini-call] вызывает `reportFailure`; `onerror` меняет состояние. | Одно событие на отказ со стадией и close code при наличии. | `realtime-audio.test.mjs`, `onerror` без `onclose`, переполнение и гонка reconnect. | Проверить |
| Playback, mute и поток захвата работают | [call.js][mini-call] отправляет данные playback и счётчики захвата. [capture.js][mini-capture] работает с PCM. | Сводка у владельца соединения. Чистая обработка аудио тихая. | Больше кадров за ту же длительность, объём записей ограничен. | Проверить |
| Клиент закрыл звонок или вкладку | [call.js][mini-call] различает end и pagehide. | Событие собственного наблюдения, итог урока у Foundation. | `end` дошёл, `end` потерян, `pagehide` во время открытия. | Проверить |
| Сеть не доставила клиентский диагностический отчёт | [api.js][mini-api] подавляет отказ `fetch`. | Тихо. Отказ телеметрии сохраняет бизнес-исход и не создаёт цикл отчётов. | Отключить endpoint телеметрии, сохранить отображение ошибки. | Код |

### Эксплуатационные и альтернативные точки запуска

| Ситуация | Владелец и текущая запись | Что писать | Проверка | Статус |
| --- | --- | --- | --- | --- |
| Deploy Ktor, AI или Mini App успешно переключил ревизию | [workflows][workflows] и deploy скрипты [infra][infra] владеют шагами. | Событие итоговой ревизии после readiness, отдельное от сборки. | Найти один итог и связать с revision в контейнере. | Проверить |
| Deploy остановился на env, образе, сети, readiness или обновлении proxy | [infra][infra] использует exit status и stderr, некоторые отказавшие шаги продолжаются. | Событие этапа с причиной и эффектом на прежнюю ревизию. | Симулировать отказ каждого этапа с тестовым Docker/SSH. | Проверить |
| Registry недоступен, deploy переиспользует локальный образ | [infra/server][infra-server] и [infra/ai-service][infra-ai] ветвятся по типу ошибки сборки. | Событие деградации и выбранного образа. Отдельный отказ при несовместимых зависимостях. | Заглушка rate limit с образами подходящей и неподходящей версии. | Проверить |
| Валидация edge завершилась или потребовалось вернуть старую конфигурацию | [install-edge.py][edge-install] и [infra/miniapp][edge] владеют изменением. | Событие применённой конфигурации либо фактического восстановления. | `test_install_edge.py`, failed validation, повторное применение и reload failure. | Проверить |
| Readiness сверяет Ktor, Python, Mini App, auth, S3 и версии | [redeploy.yml][redeploy] владеет проверкой. | Событие каждой неуспешной стадии и один успешный итог. | Запустить локальные заглушки со stale revision и отказавшим S3. | Проверить |
| PostgreSQL или Redis развёрнут, уже работает или сохранён при отказе | [postgres][infra-pg] и [redis][infra-redis] выводят причины пропусков и сохранения контейнера. | Событие результата команды без env и путей к секретам. | Уже запущенный контейнер, отсутствующая конфигурация, отказ AOF rewrite. | Проверить |
| Подготовка и проверка S3 успешна либо ресурс недоступен | [infra/storage][storage] выполняет проверку credentials и доступа. | Событие результата действия. Не выводить ключи и подписанные URLs. | Успех, missing credentials, access denied, недоступный endpoint. | Проверить |
| Backup содержит все доступные артефакты или частичный результат | [backup-remote.sh][backup] сообщает об отдельных записях и отказах. | Событие итогового состава и отдельных ошибок без содержимого. | Отказ одного из pg_dump, Redis или audio, проверка читаемости артефактов. | Проверить |
| Restore направлен в live volume или выполняется по runbook | [backup-remote.sh][backup] отказывает в restore. [Operations][operations] задаёт отдельную процедуру. | Событие отказа скрипта или подтверждённого восстановления в отдельном окружении. | Отказ live volume, rehearsal в throwaway volume, replay удаления. | Проверить |
| Экспорт TutorStore, import и проверка marker | [export_tutor_state.py][export] экспортирует источник. FoundationImportCli пишет `foundation_cutover` и отказ команды. | Событие оператора с итогом сверки и номером ревизии, без экспортированных данных. | Из JAR проверены новый cutover, повтор, неверная контрольная сумма и несовпавший манифест. Отдельно проверяется частичный экспорт. | Частично |
| Retention maintenance и deletion ledger | FoundationMaintenanceCli пишет `foundation_deletion_ledger`; [FoundationJobs][jobs] запускает периодический retention. | Событие причины запуска и результата commit. | Из JAR проверены экспорт, текущий ledger, восстановление и откат при конфликте. Сводка периодического retention открыта. | Частично |
| Каталог проверен, импортирован или опубликован | Ktor CLI пишет `catalog_validate`, `catalog_import`, `catalog_publish` и `catalog_cli_failed`. [catalog-release-remote.py][catalog-cli] сверяет SHA. | Событие результата с версией, отдельно import и publish. | Из JAR проверены исходы и повтор. `test_catalog_release_remote.py` проверяет несовпадение SHA и наличие подтверждения ревью. | PASS для проверенных исходов |
| Alloy, Loki или мониторинг не готовы или восстановлены | [infra/monitoring][monitoring] и [infra/ai-service][infra-ai] владеют конфигурацией и запуском. | Событие результата без URL с credentials. Диагностика записи и доставка логов имеют разные исходы. | Пропуск из-за конфигурации, отказ доставки, восстановление. | Проверить |
| Отчёт или alert отправлен, подавлен либо получил отказ | [reporter][reporter] реализует ежедневный отчёт и credit monitor. | Событие отправки или причины штатного подавления. Повторы одной причины агрегировать. | `test_daily_report.py` и `test_credit_monitor.py`, недостаток данных и сбой Telegram. | Проверить |
| Агентский dialogue, voice, foundation-check и подготовка аудио | [infra/dev][dev-tools] запускается вручную или из DEV workflow. | Событие результата инструмента с кодом и артефактом. Fixture и производственный вход различимы. | `test_agent_voice.py` и `test_foundation_check_remote.py`, пропуск и отказ. | Проверить |
| Нагрузочный запуск или единичный eval | [load][load], [evals][evals] и отдельный [Python load handler][python-load] имеют собственные точки входа. | Сводка сценария, частоты и причин отказов. Экспорт результатов в явный артефакт. | Offline fixture, обрыв и таймаут. Проверить, что итог отражает число попыток и остановку по лимиту. | Проверить |
| Сборка Mini App, генерация материалов каталога, CI и локальные тесты | [mini-app/scripts][mini-scripts], [scripts][scripts] и [workflows][workflows] запускают команды разработки. | Результат команды у инициатора. Чистый рендер без runtime-событий. | Ошибка входа и выходной код, успешный артефакт. | Проверить |
| Legacy calls, clips, onboarding, reminders, Vocabulary и dashboards | [Python legacy][python-legacy] и [Ktor legacy][ktor-legacy] содержат альтернативный wiring. Shipping roots его не монтируют. | Тихо в текущей поставке. Явный legacy запуск пишет собственные исходы, их ветки требуют аудита. | `test_speaky_runtime.py`, проверить отсутствие legacy routes в shipping app. | Вне поставки |
| CMP Android, iOS и Desktop | [CMP][cmp-prototype] запускается отдельными хостами. Legacy app API отсутствует в shipping root. | При запуске прототипа писать свои lifecycle, auth, сеть и WebRTC. | Прошли JVM и Android host тесты. Требуются запуски приложений и управление отказами транспорта в каждой среде. | Частично |
| Тестовая реализация, fixture или чистая функция вычисления | [тесты Python][python-tests], [тесты Kotlin][ktor-tests] и [тесты Mini App][mini-tests]. | Тихо в производственном выводе. Существенные решения фиксирует вызывающий владелец. | Проверить wiring и отсутствие fixture событий при штатном запуске. | Вне поставки |

## Граница доказательств

Таблица отражает текущий статус каждой ситуации. `PASS по коду` подтверждает регистрацию или явное правило записи. `Частично` указывает проверенный исход и оставшуюся ветку. Для `Код`, `Проверить` и `Пробел` ещё нужны доказательства по фактическому выводу. `Вне поставки` подтверждает границу текущего запуска.

Процент покрытия по таблице пока преждевременен. Для него сначала разбейте строки со статусом «Проверить» на отдельные исходы там, где их причины, владелец или проверка различаются. Затем подтвердите запись у владельца, тихий путь и отсутствие повторной ошибки. Для изменения состояния сопоставьте строку с commit или receipt.

## Реализация в рабочем дереве

Эта таблица сводит результаты по критериям приёмки.

| Критерий | Статус | Доказательство и граница |
| --- | --- | --- |
| Общий формат и сохранение маркеров | PASS для проверенных writers | [Схема v1](../../contracts/logging/v1/envelope.json). Тесты разбирают реальные строки Ktor и Python. Поля провайдера не заменяют общие поля. |
| Запуск, остановка и часть отказов конфигурации | PASS локально | Запуск из собранного Ktor JAR подтвердил `/health=200`, по одному событию старта и остановки и причину пропуска TrialWorker без токена. Отдельные запуски проверили ошибку конфигурации, недоступный сертификат и отсутствие отметки cutover. Python проверяет старт и остановку через shipping factory в `test_speaky_runtime.py`. |
| Изменение очереди и rollback | PASS на PostgreSQL 16 | `committedQueueTransitionsAreVisibleAndEmptyPollsAndRollbackAreQuiet` проверяет ровно две записи для постановки и истечения источника. Пустой опрос и откат проходят тихо. |
| Trial Assessment и Roadmap | PASS для проверенных веток | `completedTrialObservationJobAppliesRealSourceOnceAndRejectsListening` проверяет `superseded` и причину. `assessmentWritesEvidenceBoundStateWithoutChangingSelfReport` проверяет одну запись ревизии и отдельный исход replay. `assessmentLogsWhyRoadmapWasSkippedWithoutEnqueueing` проверяет три причины пропуска и ровно одну запись на каждый сценарий. |
| Отправка Roadmap | PASS на PostgreSQL 16 и HTTP-заглушке | `completedUnpaidTrialSendsSavedRoadmapOnceAndKeepsUnknownReceipt` различает подтверждение, HTTP 502 без подтверждения и явный отказ Bot API. При двух последних исходах сохраняется `unknown`, строка об успехе отсутствует, повторной отправки нет. |
| Смена попытки подготовки и ручная ревизия Roadmap | PASS на PostgreSQL 16 | При двух одновременных подготовках первая получает `superseded` после истечения аренды. Только вторая сохраняет тело сообщения. Новая ручная ревизия с начальной приоритизацией не присваивает себе старую генерацию. Запись старой схемы продолжает обрабатываться. |
| Отмена Trial followup при повторном входе | PASS локально | Открытие звонка фиксирует `cancelled/call_active` после commit. Worker пишет `superseded/notice_state_changed` с тем же ID напоминания и trace. PostgreSQL и HTTP-заглушка подтверждают отсутствие отправки. |
| Имена Foundation-команд | PASS по исходникам | У всех 80 веток dispatch есть диагностическое имя. Отдельные ветки внутри команд продолжают проверяться по матрице. |
| Связь входа, попытки и серверного исхода | PASS для проверенных HTTP и WS | Python-тесты проверяют доверенные `job_id`, `fence` и `attempt`. У сокета один серверный trace. Клиентский UUID остаётся отдельным полем. |
| Ошибка попытки и решение о повторе | PASS локально | `workerLinksRetryAndTerminalDecisionsToTheirOwnPrimaryFailure` проверяет две попытки одной задачи, один стек транспортного сбоя и общий ID для отказа AI, решения worker и конечного состояния. Ответ с контрольным секретом отсутствует в выводе. |
| Клиентские исходы | PASS локально | `npm test`, 19 тестов. Вызовы клиента различают транспорт, разбор, отрисовку и пользовательские действия. Кадры и heartbeat не создают поток сообщений диагностики. |
| Голос, передача кадра и отказ WebSocket | PASS для проверенных веток | `test_voice_socket_uses_one_server_trace_through_rejection` проверяет ровно две записи и общий trace. `test_audio_batch_success_is_quiet_and_failure_keeps_correlation` проверяет тихий успех и связанный отказ. Тест reconnect проверяет 30 heartbeat без строк. |
| Deploy и backup | PASS для проверенных веток | Десять сценариев с заглушкой Docker проверили стадии, исходы и код возврата. Прямой запуск backup CLI подтвердил одну строку для ошибки аргументов и по одной для запрета restore в тома PostgreSQL и Redis. Совместная выкладка Ktor и Python прошла на DEV. Рабочий backup на DEV требует отдельной проверки. |
| Команды каталога | PASS локально | Запуск CLI из JAR на изолированной PostgreSQL проверил валидацию, сохранение и активацию релиза. Повтор импорта и публикации дали `replayed`. Невалидный манифест и отсутствующая версия дали `rejected`. Контрольный секрет из манифеста отсутствовал в выводе. |
| Импорт и журнал удалений | PASS локально для проверенных веток | Запуски CLI из JAR на изолированной PostgreSQL проверили новую отметку импорта, повтор, неверную сумму, экспорт с правами `0600`, восстановление и конфликт. Конфликт откатил вставку предыдущей записи. Секреты и тела записей отсутствуют в логах. |
| Ограничение шума | PASS для проверенных политик | Пять парных прогонов на одном SLF4J appender. Для 1 000 продлений отдельные записи дали 3 001 строку и около 1,50 МБ. Сводка дала одну строку в 525 байт со счётчиком 1 000. Измерены сериализация и выделения памяти в писателе. Задержка запроса и фактическая запись на диск требуют отдельных замеров. Прогон повтора отсрочки подтвердил первый факт для каждого задания и итог `suppressed_count=999`. |
| Полное покрытие исполняемых веток | FAIL | Среди открытых проверок есть отказ миграции при старте, конкретные отказы Mini App, сводка периодического retention и фактический запуск CMP на каждом хосте. Оставшиеся исходы принадлежат строкам `Частично`, `Код`, `Проверить` и `Пробел`. |
| Восстановление цепочки Trial, Assessment, Roadmap, Telegram из БД | PASS на изолированной PostgreSQL с HTTP-заглушкой | `assessmentWritesEvidenceBoundStateWithoutChangingSelfReport` повторяет генерацию и подготовку сообщения. Для фоновой генерации из PostgreSQL восстанавливаются запись доставки, точная попытка генерации, Assessment, Observation и ID урока. Первая ревизия Roadmap сохраняет ID генерации и `fence` в истории. Для записей старой схемы ссылка может отсутствовать. |
| Ошибки вывода | PASS локально для проверенных обработчиков | `test_runtime_stream_loss_is_reported_on_the_next_write` и `logbackStreamFailureLeavesTheOperationOutcomeAndReportsTheLoss` проверяют отказ потока и счётчик потерь. Реальный отказ диска или процесса на DEV требует отдельной проверки. |
| DEV, связь HTTP-отказа между Ktor и Python | PASS | На `7c304b3d` оба контейнера подтвердили ревизию. Автопроверка нашла `backend_failed` и `foundation_failed` в Docker-логах с теми же `trace_id=7de731d08a0943c79c283530f43f347b` и `error_id=2cd256a322c14ae686e80b5b9993733e`. [Вывод GitHub Actions](https://github.com/greenblat17/1billion-dollar-startup/actions/runs/44931398602/job/136929519701). |
| DEV, сохранённая связь задач Roadmap и фактическая доставка | BLOCKED | На DEV подтверждён контрольный отказ авторизации. V67 проверена локально. Для DEV нужны поставка миграции и согласованный сценарий доставки. |

Локально прошли 1 063 теста Python, 345 тестов Ktor, `buildFatJar`, `detekt`, по 37 тестов CMP JVM и Android host, 19 тестов и сборка Mini App. На PostgreSQL 16 прошли 137 тестов Foundation. Существующий HTTP harness на свободном порту выполнил 63 теста основного сервиса и 7 тестов legacy-адаптеров. После прохода Foundation отдельно проверены два сценария cutover, три сценария уведомлений и 21 тест диагностики Python.

[ktor-diag]: ../../cmp/server/src/main/kotlin/com/eliteteam/speakingcoach/RuntimeDiagnostics.kt
[python-diag]: ../../ai-service/app/diagnostics.py
[ktor-root]: ../../cmp/server/src/main/kotlin/com/eliteteam/speakingcoach/SpeakyApplication.kt
[foundation]: ../../cmp/server/src/main/kotlin/com/eliteteam/speakingcoach/foundation/
[foundation-routes]: ../../cmp/server/src/main/kotlin/com/eliteteam/speakingcoach/foundation/FoundationRoutes.kt
[service]: ../../cmp/server/src/main/kotlin/com/eliteteam/speakingcoach/foundation/FoundationService.kt
[trial-routes]: ../../cmp/server/src/main/kotlin/com/eliteteam/speakingcoach/foundation/TrialRoutes.kt
[trial-access]: ../../cmp/server/src/main/kotlin/com/eliteteam/speakingcoach/foundation/TrialAccess.kt
[agent-routes]: ../../cmp/server/src/main/kotlin/com/eliteteam/speakingcoach/foundation/AgentTestRoutes.kt
[webhook]: ../../cmp/server/src/main/kotlin/com/eliteteam/speakingcoach/telegram/TelegramWebhook.kt
[telegram]: ../../cmp/server/src/main/kotlin/com/eliteteam/speakingcoach/telegram/SpeakyTelegram.kt
[billing]: ../../cmp/server/src/main/kotlin/com/eliteteam/speakingcoach/telegram/MockBillingTelegram.kt
[mock-payment]: ../../cmp/server/src/main/kotlin/com/eliteteam/speakingcoach/foundation/MockPaymentAdapter.kt
[intake]: ../../cmp/server/src/main/kotlin/com/eliteteam/speakingcoach/foundation/TelegramTextIntake.kt
[chat]: ../../cmp/server/src/main/kotlin/com/eliteteam/speakingcoach/foundation/FoundationChat.kt
[runs]: ../../cmp/server/src/main/kotlin/com/eliteteam/speakingcoach/foundation/ConversationRuns.kt
[tutor-client]: ../../cmp/server/src/main/kotlin/com/eliteteam/speakingcoach/ai/TutorClient.kt
[jobs]: ../../cmp/server/src/main/kotlin/com/eliteteam/speakingcoach/foundation/FoundationJobs.kt
[worker]: ../../cmp/server/src/main/kotlin/com/eliteteam/speakingcoach/foundation/FoundationWorker.kt
[review]: ../../cmp/server/src/main/kotlin/com/eliteteam/speakingcoach/foundation/PostLessonReview.kt
[assessment]: ../../cmp/server/src/main/kotlin/com/eliteteam/speakingcoach/foundation/TrialAssessment.kt
[background]: ../../cmp/server/src/main/kotlin/com/eliteteam/speakingcoach/foundation/BackgroundContracts.kt
[trial-worker]: ../../cmp/server/src/main/kotlin/com/eliteteam/speakingcoach/foundation/TrialWorker.kt
[delivery]: ../../cmp/server/src/main/kotlin/com/eliteteam/speakingcoach/foundation/InitialRoadmapDelivery.kt
[ktor-legacy]: ../../cmp/server/src/main/kotlin/com/eliteteam/speakingcoach/legacy/LegacyApplication.kt
[ktor-tests]: ../../cmp/server/src/test/
[ai-docker]: ../../ai-service/Dockerfile
[python-root]: ../../ai-service/app/speaky_main.py
[python-foundation]: ../../ai-service/app/foundation_api.py
[python-legacy]: ../../ai-service/app/main.py
[python-load]: ../../ai-service/app/load_test.py
[python-tests]: ../../ai-service/tests/
[trial-api]: ../../ai-service/app/trial_api.py
[audio]: ../../ai-service/app/trial_audio.py
[realtime]: ../../ai-service/app/realtime_voice/
[realtime-connection]: ../../ai-service/app/realtime_voice/connection.py
[realtime-session]: ../../ai-service/app/realtime_voice/session.py
[client]: ../../ai-service/app/foundation_client.py
[context]: ../../ai-service/app/context_builder.py
[tutor]: ../../ai-service/app/foundation_tutor.py
[tutor-diag]: ../../ai-service/app/tutor_diagnostics.py
[provider]: ../../ai-service/app/provider_call_log.py
[learning]: ../../ai-service/app/learning_engine.py
[evals]: ../../ai-service/evals/
[export]: ../../ai-service/scripts/export_tutor_state.py
[mini-nginx]: ../../mini-app/nginx.conf
[mini-app]: ../../mini-app/src/app.js
[mini-api]: ../../mini-app/src/api.js
[mini-progress]: ../../mini-app/src/progress.js
[mini-schedule]: ../../mini-app/src/schedule.js
[mini-call]: ../../mini-app/src/call.js
[mini-capture]: ../../mini-app/src/capture.js
[mini-tests]: ../../mini-app/tests/
[mini-scripts]: ../../mini-app/scripts/
[edge]: ../../infra/miniapp/
[edge-install]: ../../infra/miniapp/install-edge.py
[infra]: ../../infra/
[infra-server]: ../../infra/server/
[infra-ai]: ../../infra/ai-service/
[infra-pg]: ../../infra/postgres/
[infra-redis]: ../../infra/redis/
[storage]: ../../infra/storage/
[backup]: ../../infra/backup/backup-remote.sh
[monitoring]: ../../infra/monitoring/
[reporter]: ../../infra/monitoring/reporter/
[dev-tools]: ../../infra/dev/
[load]: ../../load/
[scripts]: ../../scripts/
[catalog-cli]: ../../infra/dev/catalog-release-remote.py
[workflows]: ../../.github/workflows/
[catalog-workflow]: ../../.github/workflows/catalog-dev-release.yml
[redeploy]: ../../.github/workflows/redeploy.yml
[cmp-prototype]: ../../cmp/app/
[operations]: ../integrations/operations.md
