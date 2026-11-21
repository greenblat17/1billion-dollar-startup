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
Учебные поля читаются из Student Learning State отдельно от MemorySelector. Запись цели, CEFR, интенсивности и Homework через Memory отключена; новые AI-writers относятся к будущему SP-05. [Контракт SP-03 v5.5](../../architecture.md#student-learning-state-sp-03-v55).
Shipping root использует `TrialTutorModel(OpenAiChatModel(..., KORA_MODEL))`, без voice allowlist.

ContextBuilder получает актуальный snapshot из backend, выбирает память и ограниченную историю.
Core и запреты упоминания сохраняются независимо от relevance-выборки. Подробный контракт
происхождения, retention и forget находится в [архитектуре](../../architecture.md#память-репетитора-sp-07)
и [SP-07](../plans/2026-11-12-sp07-memory.md); отдельного Redis-профиля активного чата нет.

### Первое приветствие

Каждый новый `/start` обрабатывают `TelegramIntroduction` и `TutorIntroductionDelivery`, без model call.
Четыре коротких сообщения знакомят с Сашей, дают примеры разговорной практики и спрашивают о цели.
V47 хранит независимую от профиля границу `/start` по chat/message id. В одной транзакции старый профиль удаляется через существующий deletion/epoch механизм, создаётся новый Student и серия приветствий. Память, история, уроки, расписание, прогресс и подписка очищаются; задания отменяются, аудио ставится в очередь purge. Старые capabilities отзываются.
Повтор той же или более старой команды не сбрасывает снова. Более старые intake-сообщения гасятся; ответы после `/start` сохраняются и могут прервать серию. Сброс не удаляет уже видимые сообщения из Telegram и импортированные календарные события.
Во вступительных репликах нет клавиатуры; ответы режима `dialogue` сохраняют My Progress.

`telegram_introduction` хранит request, epoch, позицию и время следующей реплики;
`telegram_intro_delivery` — отдельный claim/receipt каждой отправки. Intake отменяет остаток серии
в той же транзакции, которая сохраняет новое сообщение до ACK. Claim и intake используют общий chat lock.
Паузы проходят вне транзакции, с проверкой отмены каждые 100 мс и перед claim следующей отправки.
Первая реплика идёт сразу. Длина предыдущего текста определяет паузу до следующей реплики:
500 мс плюс 42 мс на символ, от 1,1 до 6,5 секунды. Dispatch других чатов продолжает работу.
Новое сообщение прерывает оставшиеся паузы и обрабатывается после выхода серии.
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

[scheduling_tools.py](../../ai-service/app/scheduling_tools.py) переводит выбранные моделью инструменты
в команды Ktor и формирует описание пробного урока. Ktor проверяет источник поручения, версии,
время и допустимость изменений. Он хранит запись и управляет её уведомлениями.
[Контракт v1](../../contracts/scheduling/v1/commands.json) охватывает создание, перенос, отмену и список уроков.
Trial читает активную запись из snapshot, а повтор проверяет через receipt. Regular повторяет ту же команду с тем же operation ID.

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

После commit будущего Trial Telegram отправляет одно текстовое подтверждение.
Оно показывает дату, время с поясом и длительность. Кнопка «Начать урок» ведёт к конкретной
Lesson. До начала Mini App показывает ожидание по серверному времени. Voice admission проверяет
время и владельца на сервере. «Добавить в календарь» остаётся отдельной кнопкой.

Перенос и отмена редактируют существующую карточку. Ранее доставленные HTML-виджеты сохраняют свой
путь редактирования. Telegram `message is not modified` считается успехом.
Отправка или редактирование получают durable claim до вызова Bot API. Сбой оставляет исход
`unknown` и освобождает intake. Replay сохраняет урок и не повторяет внешнюю отправку.
На новый вопрос о подключении репетитор читает актуальную запись через `get_trial_booking`.
Кнопка в ответе допускается для открытой исполняемой записи ученика, пока её время действует.
После отклонённой попытки изменить Trial ответ сверяется с текущей записью.

При `timing=now` TrialWorker отправляет единственное приглашение с кнопкой. Future и now
завершают ход сразу после подтверждённой записи, без ещё одного вызова модели.
Обычные реплики пишутся на «ты» без Telegram-цитаты входящего сообщения. При первом предложении
Саша приглашает на бесплатный получасовой созвон сейчас или в другое удобное время.
Явные поля обучения сохраняются в текущем ходе, с исходной репликой как доказательством.
Из групповой записи сохраняются только поля с точной цитатой из этого сообщения.

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
Прямые типизированные запросы Telegram сериализуются через `telegramRequestJson`, без null и необязательных значений по умолчанию. Storage-сериализатор `foundationJson` для них не используется: `reply_parameters=null` Telegram отклоняет.
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

| Ответственность | Исходники |
| --- | --- |
| Запуск приложения | [SpeakyApplication.kt](../../cmp/server/src/main/kotlin/com/eliteteam/speakingcoach/SpeakyApplication.kt), [speaky_main.py](../../ai-service/app/speaky_main.py) |
| Входящие сообщения, обработка и отправка | [TelegramTextIntake.kt](../../cmp/server/src/main/kotlin/com/eliteteam/speakingcoach/foundation/TelegramTextIntake.kt), [TelegramIntroduction.kt](../../cmp/server/src/main/kotlin/com/eliteteam/speakingcoach/foundation/TelegramIntroduction.kt), [ConversationRuns.kt](../../cmp/server/src/main/kotlin/com/eliteteam/speakingcoach/foundation/ConversationRuns.kt), [FoundationChat.kt](../../cmp/server/src/main/kotlin/com/eliteteam/speakingcoach/foundation/FoundationChat.kt), [TutorClient.kt](../../cmp/server/src/main/kotlin/com/eliteteam/speakingcoach/ai/TutorClient.kt) |
| Telegram, карточки и форматирование | [SpeakyTelegram.kt](../../cmp/server/src/main/kotlin/com/eliteteam/speakingcoach/telegram/SpeakyTelegram.kt), [TutorTelegramChat.kt](../../cmp/server/src/main/kotlin/com/eliteteam/speakingcoach/telegram/TutorTelegramChat.kt), [TutorIntroductionDelivery.kt](../../cmp/server/src/main/kotlin/com/eliteteam/speakingcoach/telegram/TutorIntroductionDelivery.kt), [TutorTextFormatting.kt](../../cmp/server/src/main/kotlin/com/eliteteam/speakingcoach/telegram/TutorTextFormatting.kt), [MockBillingTelegram.kt](../../cmp/server/src/main/kotlin/com/eliteteam/speakingcoach/telegram/MockBillingTelegram.kt) |
| Модель и инструменты | [foundation_tutor.py](../../ai-service/app/foundation_tutor.py), [trial_tools.py](../../ai-service/app/trial_tools.py), [memory_tools.py](../../ai-service/app/memory_tools.py), [subscription_tools.py](../../ai-service/app/subscription_tools.py) |
| Контекст | [context_builder.py](../../ai-service/app/context_builder.py), [memory_context.py](../../ai-service/app/memory_context.py), [memory_policy.py](../../ai-service/app/memory_policy.py) |
| Backend API и операции | [FoundationRoutes.kt](../../cmp/server/src/main/kotlin/com/eliteteam/speakingcoach/foundation/FoundationRoutes.kt), [FoundationService.kt](../../cmp/server/src/main/kotlin/com/eliteteam/speakingcoach/foundation/FoundationService.kt), [LessonManagement.kt](../../cmp/server/src/main/kotlin/com/eliteteam/speakingcoach/foundation/LessonManagement.kt), [MemoryOperations.kt](../../cmp/server/src/main/kotlin/com/eliteteam/speakingcoach/foundation/MemoryOperations.kt) |

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
