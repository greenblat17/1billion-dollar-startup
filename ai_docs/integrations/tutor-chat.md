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
Повторный `/start`, запись, отказ и отмена не сбрасывают отношения с репетитором.
Нет обязательной анкеты, keyword routing или терминального состояния чата.
Саша отвечает по-русски; обычные голосовые сообщения не запускают STT/TTS.
Личность задают `kora_personality.py` и `RELATIONSHIP_POLICY`; доступные действия — `TEXT_CAPABILITIES`.
Shipping root использует `TrialTutorModel(OpenAiChatModel(..., KORA_MODEL))`, без voice allowlist.

ContextBuilder получает актуальный snapshot из backend, выбирает память и ограниченную историю.
Core и запреты упоминания сохраняются независимо от relevance-выборки. Подробный контракт
происхождения, retention и forget находится в [архитектуре](../../architecture.md#память-репетитора-sp-07)
и [SP-07](../plans/2026-11-12-sp07-memory.md); отдельного Redis-профиля активного чата нет.

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
Перенос/отмена обновляют сохранённую карточку; ошибка редактирования не отменяет бизнес-операцию.
`message is not modified` считается успешным повтором. Обычная реплика не повторяет карточку записи.

My Progress — постоянная reply keyboard со следующим обычным ответом, если задан HTTPS `MINI_APP_URL`.
Она открывает `?view=progress`; чтение не требует урока или entitlement и не запускает learning jobs.
Mock checkout отправляет отдельную backend-карточку с условиями до тестового платежа;
LLM не подтверждает оплату. Реальный платёжный provider не подключён.

Ответы и follow-up преобразуют `**жирный**`, inline code и fenced code в Telegram `entities`.
Остальная разметка остаётся текстом; parse mode не включён. Длинный ответ делится после
преобразования с корректными UTF-16 offsets и целыми surrogate pairs.

Delivery claim фиксируется до внешнего вызова. Unknown не повторяется автоматически;
Telegram не предоставляет общей гарантии exactly-once. Карточка имеет fallback на новую отправку
при ошибке редактирования, поэтому отсутствие дублей при сетевой неопределённости не обещается.
Исход доставки reply/card/payment и бизнес-операция — разные факты.

## Telegram: текущая маршрутизация

| Вход | Активный обработчик |
| --- | --- |
| Private `/start`, `/onboarding` | Начать/продолжить того же репетитора |
| Другой private text, включая прежние команды | Обычный tutor turn; legacy command handlers не подключены |
| Голос, другой нетекстовый контент, group chat | Не входит в текстовый путь |
| Mock payment callback | Проверка webhook secret, private sender/owner, затем SubscriptionBilling |
| Web App | Отдельная проверка подписанного initData через TelegramMiniAuth |

Webhook проверяет `X-Telegram-Bot-Api-Secret-Token`; private-text input сохраняется **до** HTTP ACK.
Успешный ACK не означает готовый ответ. Ошибку хода может восстановить durable intake.
Во время генерации показывается `typing`. Bot setup регистрирует `/start`.
Напоминания, приглашения, no-show и follow-up обслуживает активный `TrialWorker`, а не legacy scheduler.
Условия запуска worker и диагностика находятся в [operations](operations.md).

### Карта кода

Пути Kotlin относительны к `cmp/server/src/main/kotlin/com/eliteteam/speakingcoach/`.

| Ответственность | Источник |
| --- | --- |
| Shipping wiring | `SpeakyApplication.kt`; `ai-service/app/speaky_main.py` |
| Inbox, runs, dispatch | `foundation/TelegramTextIntake.kt`, `ConversationRuns.kt`, `FoundationChat.kt`; `ai/TutorClient.kt` |
| Telegram / карточки / entities | `telegram/SpeakyTelegram.kt`, `TutorTelegramChat.kt`, `TutorTextFormatting.kt`, `MockBillingTelegram.kt` |
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
