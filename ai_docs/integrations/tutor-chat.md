# Текстовый репетитор и Telegram

Активный путь — `SpeakyApplication.kt` → PostgreSQL foundation → `app.speaky_main`.
Продуктовые правила знакомства — в [SP-01](../plans/2026-11-09-sp01-text-onboarding.md),
поставка и приёмка — только в [статусе](../status.md).

- [SP-01: постоянный текстовый чат с Сашей](#sp-01-постоянный-текстовый-чат-с-сашей)
- [Telegram: текущая маршрутизация](#telegram-текущая-маршрутизация)
- [Совместимость сохранённых систем](#совместимость-сохранённых-систем)

## SP-01: постоянный текстовый чат с Сашей

### Реализованный путь

```text
Private Telegram text → durable inbox → FoundationChat / conversation run
  → /internal/foundation/tutor/turn → ContextBuilder → FoundationTutor / native tools
  → scoped backend commands → PostgreSQL receipt → reply / delivery receipt
```

Один Student, общая память, цель и история обслуживают Telegram, голосовые уроки и learning.
Запись, отказ и отмена не сбрасывают отношения. Каждый новый `/start` полностью сбрасывает пользователя во всех окружениях.
Нет обязательной анкеты, keyword routing или терминального состояния чата.
Саша отвечает по-русски; обычные голосовые сообщения не запускают STT/TTS.
Характер задаёт `kora_personality.py`; активные текстовые правила и стратегии — `text_tutor_policy.py`.
`ContextBuilder` добавляет характер и только стратегию переданного backend `textMode`:
`introduction` или `dialogue`. `FoundationTutor` добавляет общие правила и `TEXT_CAPABILITIES`.
Legacy `RELATIONSHIP_POLICY` с пятью темами не входит в активный prompt; голосовая личность не меняется.

`textMode=introduction` применяется к ученику с новой durable-последовательностью приветствия без созданных Lesson.
Завершение/прерывание четырёх реплик, вопрос, отказ или смена темы не завершает консультацию.
Существующие отношения без такой последовательности и ученики с созданным Lesson остаются в `dialogue`.
Повторный start не сбрасывает режим или память. Успешное создание Lesson завершает знакомство;
последующая отмена не возвращает его. Прямые booking tools остаются доступны без анкеты. В режиме знакомства My Progress скрыт, в обычном диалоге доступен.
Самооценка `level` входит в core памяти, в том числе при сбое relevance-selector.
Shipping root использует `TrialTutorModel(OpenAiChatModel(..., KORA_MODEL))`, без voice allowlist.

ContextBuilder получает актуальный snapshot из backend, выбирает память и ограниченную историю.
Core и запреты упоминания сохраняются независимо от relevance-выборки. Подробный контракт
происхождения, retention и forget находится в [архитектуре](../../architecture.md#память-репетитора-sp-07)
и [SP-07](../plans/2026-11-12-sp07-memory.md); отдельного Redis-профиля активного чата нет.

### Первое приветствие

Каждый новый `/start` обрабатывают `TelegramIntroduction` и `TutorIntroductionDelivery`, без model call.
Текст и продуктовые правила заданы в [SP-01](../plans/2026-11-09-sp01-text-onboarding.md#поведение-модели).
V47 хранит независимую от профиля границу `/start` по chat/message id. В одной транзакции старый профиль удаляется через существующий deletion/epoch механизм, создаётся новый Student и серия приветствий. Память, история, уроки, расписание, прогресс и подписка очищаются; задания отменяются, аудио ставится в очередь purge. Старые capabilities отзываются.
Повтор той же или более старой команды не сбрасывает снова. Более старые intake-сообщения гасятся; ответы после `/start` сохраняются и могут прервать серию. Сброс не удаляет уже видимые сообщения из Telegram и импортированные календарные события.
Профиль и память не сбрасываются. Во вступительных репликах нет клавиатуры; ответы режима `dialogue` сохраняют My Progress.

`telegram_introduction` хранит request, epoch, позицию и время следующей реплики;
`telegram_intro_delivery` — отдельный claim/receipt каждой отправки. Intake отменяет остаток серии
в той же транзакции, которая сохраняет новое сообщение до ACK. Claim и intake используют общий chat lock.
Паузы проходят вне транзакции, с проверкой отмены каждые 100 мс и перед claim следующей отправки.
Номинальные паузы после подтверждённых отправок — 750/1250/750 мс; первая реплика без задержки.
Typing — best effort; его ошибка не повторяет и не отменяет доставку текста.

Claim фиксирует `unknown` до Bot API. После подтверждения receipt и видимая реплика сохраняются атомарно
в общей истории. Непосланные и неизвестные реплики не выдаются LLM за доставленные.
Рестарт продолжает только ещё не начатые отправки. Ошибка отправки останавливает остаток серии;
потерянный receipt после рестарта также не вызывает повтор: активный claim защищён 150 секунд,
затем последовательность останавливается с `unknown`. Durable intake повторяет зависший dispatch через 180 секунд.
Start не создаёт незавершённый model run. Входящий ответ обрабатывается обычным сериализованным intake/run,
с существующими receipts модели и доставки. Forget/delete отменяет серию и отзывает поздние receipts по epoch.

Граница гарантии — зафиксированный send claim: уже начатый внешний вызов нельзя отозвать при новом входе.
Его подтверждённая реплика добавляется в историю перед завершением обработки start. Telegram не предоставляет
user typing или общей exactly-once отправки. При неизвестном исходе возможен пропуск реплики, но не автоматический дубль.

### Исправления ошибок инструментов

Trial `after_minutes` создаёт встречу через `delayMinutes` от исходного сообщения; пояс необязателен (UTC для отображения, если неизвестен). `shift_minutes` остаётся переносом существующей встречи. Schema режимов взаимоисключающая; лишние поля backend не принимает.

`update_learner_context` сохраняет только факты с точными цитатами текущего сообщения. Неподтверждённые поля пропускаются с явным результатом `skipped`, а не выдуманной цитатой; `trialPreference=null` оставляет прежнее значение. После двух отказов инструментов ход завершается одним LLM-ответом без доступных tools; неуспешная запись не объявляется успешной.

Ключи фоновых personal candidates используют стабильный буквенный hash, совместимый с backend validator. Это не меняет сохранённые факты и не повторяет прежние failed jobs.

### API и надёжность

Ktor вызывает `POST /internal/foundation/tutor/turn` с `TutorRequest` и backend execution scope.
Время источника — исходное `message.date`; авторитетный текст берётся из сохранённого backend input.
AI вызывает `/internal/foundation/v1/commands`, `/snapshot`, `/operations/{id}`.
Внутренние запросы используют `X-Internal-Token`, scoped операции — также `X-Execution-Scope`.
Модель не выбирает Student или scope. Карта API и владельцев — в [архитектуре](../../architecture.md#внутренние-api-и-доверие).

Backend проверяет ownership, evidence, epoch, версии, состояние и idempotency.
Lease/fence сериализуют ходы; повтор request/operation использует сохранённый результат.
После сбоя финального model call ответ опирается на committed операции, без повторной мутации.
Storage uncertainty остаётся ошибкой, а не вымышленным успехом или переходом на memory store.
Ограничение model/tool loop: 55 секунд, до шести rounds и пяти calls за round.
Это не предел всей сборки контекста: MemorySelector вызывается до этого цикла.

### Инструменты и время

| Назначение | Native tools / контракт |
| --- | --- |
| Trial | `get_trial_booking`, `create_trial_booking`, `reschedule_trial_booking`, `cancel_trial_booking` |
| Профиль | `update_learner_context`; цель, self-report и observations различаются |
| Расписание | `save_schedule`, `get_schedules`, `cancel_schedule`, `resolve_schedule_time`, `get_lessons`, `create_extra_lesson`, `reschedule_lesson`, `cancel_lesson`; [SP-04](../plans/2026-11-12-sp04-scheduling.md) |
| Подписка | `get_subscription`, `request_subscription_payment`, `cancel_subscription_renewal`; [SP-05](../plans/2026-11-12-sp05-subscription.md) |
| Память | `search_memory`, `discuss_memory`, `record_memory`, `set_memory_policy`, `review_observation`, `confirm_memory`, `reject_memory`, `forget_memory`; [SP-07](../plans/2026-11-12-sp07-memory.md) |

Схемы строгие; backend повторно проверяет аргументы и источник текущего поручения.
Ясный запрос выполняется без второго подтверждения или обязательного заполнения профиля.
Модель понимает относительную дату/город; код проверяет время и DST. Семантическая правильность
разбора требует живой проверки. Для regular `now`/`after` instant вычисляет backend при первой
записи; receipt replay не сдвигает время. Для trial сохраняются режимы времени `tutor_tools.py`.

Новая trial-запись создаёт executable Lesson. Перенос сохраняет ID; отмена сохраняет историю.
Старые placeholders не активируются массово: новое согласование расписания либо явный перенос
выбранной встречи создают доступный для admission путь. Само истечение времени не доказывает,
что ученик прошёл урок. Доступ проверяет backend, не Telegram-кнопка.

### Telegram-карточки и форматирование

«Открыть урок» получает `web_app` URL с Lesson ID, когда задан HTTPS `MINI_APP_URL`.
Без URL остаётся disabled-кнопка; это ошибка/неполнота конфигурации, а не описание возможностей Mini App.
Новая trial-карточка — `sendWidget` (Bot API 10.4): `TutorBookingDelivery.kt` хранит фиксированный HTML,
нативные цвета Telegram и изменяемый `chat_data`. Данные попадают только в `textContent`, не в HTML.
Виджет отправляется без caption: текст под ним не дублирует данные. Текстовой замены для старых клиентов нет.
Перенос/отмена редактируют данные того же сообщения и очищают прежнюю подпись. V46 сохраняет `is_widget`;
старые текстовые карточки продолжают использовать `editMessageText`.
`message is not modified` считается успехом; неизвестный результат не вызывает новую отправку.
Первичная запись доставляется тремя сообщениями: короткое «договорились», компактный HTML-виджет, завершающая реплика модели. Виджет: статус, число/месяц, диапазон времени с поясом, день недели и Саша, «Бесплатно · Онлайн в Speaky»; без брендинга и больших изображений. Под ним только «Добавить в календарь» (необязательный выбор Google / .ics). «Начать урок» доступно только в приглашении в момент начала; утреннее и часовое напоминания без кнопок. Подтверждение и виджет имеют общую границу unknown: при сбое между ними последовательность не повторяется. При unknown delivery реплика не отправляется, в том числе после рестарта. В ответе с записью скрыта навигация My Progress; обычный последующий диалог её сохраняет.

Редактирование данных и очистка подписи/обновление кнопок — два вызова: при сбое между ними часть представления может остаться старой;
ошибка доставки не откатывает запись и не создаёт второй урок. Автоматическое восстановление unknown не обещается.
Контракт виджета: [Telegram Widgets](https://core.telegram.org/bots/widgets).

«Добавить в календарь» открывает `?view=calendar&lesson=…`, без микрофона и звонка.
`/miniapp/api` action `calendar` проверяет Telegram initData и владение через `TrialAccess`, затем
`lesson_calendar.py` формирует Google template URL и UTF-8 `.ics` с UTC временем, стабильным UID и SEQUENCE.
Повторное открытие читает актуальную Lesson. Отмена убирает Google-ссылку, ICS содержит CANCELLED.
Нет OAuth, новой регистрации или подписки на обновления: импортированное событие ученик меняет сам.
Сохранение файла использует системное меню share, если доступно, иначе download; Telegram iOS/Android требуют живой проверки.

Для trial `TrialReminders.kt` задаёт утреннее 08:00, напоминание за час и приглашение в начале.
Worker использует lesson version и durable receipts, не вызывает LLM ради этих уведомлений.
Утреннее окно — одна минута; поздняя запись, ранний урок и совпадение с часовым напоминанием
не создают лишнего сообщения. Запись ближе чем за час не получает часовое напоминание.
Перенос/отмена инвалидируют старую версию; неизвестная отправка не повторяется после рестарта.
Обычный вопрос не отменяет согласованное напоминание. Regular/follow-up сохраняют существующую генерацию.

При неявке через 10 минут — один мягкий check-in без обвинений с предложением перенести; подключение или отмена подавляют его.

My Progress — постоянная reply keyboard с ответом режима `dialogue`, если задан HTTPS `MINI_APP_URL`.
Она открывает `?view=progress`; чтение не требует урока или entitlement и не запускает learning jobs.
Mock checkout отправляет отдельную backend-карточку с условиями до тестового платежа;
LLM не подтверждает оплату. Реальный платёжный provider не подключён.

Ответы и follow-up преобразуют `**жирный**`, inline code и fenced code в Telegram `entities`.
Остальная разметка остаётся текстом; parse mode не включён. Длинный ответ делится после
преобразования с корректными UTF-16 offsets и целыми surrogate pairs.

Delivery claim фиксируется до внешнего вызова. Unknown не повторяется автоматически;
Telegram не предоставляет общей гарантии exactly-once. Карточка не имеет fallback на новую отправку при ошибке редактирования; unknown может оставить старое представление.
Исход доставки reply/card/payment и бизнес-операция — разные факты.

## Telegram: текущая маршрутизация

| Вход | Активный обработчик |
| --- | --- |
| Private `/start` | Полностью сбросить профиль и начать знакомство заново |
| Private `/onboarding` | Обычное текстовое сообщение репетитору |
| Другой private text, включая прежние команды | Обычный tutor turn; legacy command handlers не подключены |
| Голос, другой нетекстовый контент, group chat | Не входит в текстовый путь |
| Mock payment callback | Проверка webhook secret, private sender/owner, затем SubscriptionBilling |
| Web App | Отдельная проверка подписанного initData через TelegramMiniAuth |

Webhook проверяет `X-Telegram-Bot-Api-Secret-Token`; private-text input сохраняется **до** HTTP ACK.
Успешный ACK не означает готовый ответ. Ошибку хода может восстановить durable intake.
Во время генерации и пауз первого приветствия показывается `typing`. Bot setup регистрирует `/start`.
Напоминания, приглашения, no-show и follow-up обслуживает активный `TrialWorker`, а не legacy scheduler.
Условия запуска worker и диагностика находятся в [operations](operations.md).

### Карта кода

Пути Kotlin относительны к `cmp/server/src/main/kotlin/com/eliteteam/speakingcoach/`.

| Ответственность | Источник |
| --- | --- |
| Shipping wiring | `SpeakyApplication.kt`; `ai-service/app/speaky_main.py` |
| Inbox, runs, dispatch | `foundation/TelegramTextIntake.kt`, `TelegramIntroduction.kt`, `ConversationRuns.kt`, `FoundationChat.kt`; `ai/TutorClient.kt` |
| Telegram / карточки / entities | `telegram/SpeakyTelegram.kt`, `TutorTelegramChat.kt`, `TutorIntroductionDelivery.kt`, `TutorTextFormatting.kt`, `MockBillingTelegram.kt` |
| Модель и tools | `ai-service/app/foundation_tutor.py`, `trial_tools.py`, `memory_tools.py`, `subscription_tools.py` |
| Контекст | `ai-service/app/context_builder.py`, `memory_context.py`, `memory_policy.py` |
| Backend API и операции | `foundation/FoundationRoutes.kt`, `FoundationService.kt`, `LessonManagement.kt`, `MemoryOperations.kt` |

## Совместимость сохранённых систем

`app.main`, Redis `TutorStore`, `LearnerStore`, `TelegramHandlers.kt`, calls/reviews/vocabulary,
старые reminders, dashboards и CMP app API сохранены, но shipping roots их не монтируют.
`/internal/tutor/start|turn|delivery`, `/internal/learners/{sessionId}/context` и старые analytics endpoints
относятся к этому legacy root. Они не описывают текущий PostgreSQL-контракт.

Импортирован только прежний TutorStore по [cutover runbook](operations.md#offline-tutorstore-cutover).
`learner:`, `assessment:`, practice/campaign namespaces не объединены с новым профилем.
Сохранение файлов не доказывает активную маршрутизацию; старый voice onboarding не возвращается.
Legacy-контракты: [голос/review](voice-and-reviews.md), [практика](practice.md),
[аналитика](analytics.md), [CMP API](mobile-api.md). Прежние результаты проверок доступны в Git;
они не доказывают новый Telegram UX или текущее состояние PROD.
