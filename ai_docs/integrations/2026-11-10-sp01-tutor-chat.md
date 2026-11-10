# SP-01: постоянный текстовый чат с Сашей

Реализация развёрнута на DEV; живая диалоговая приёмка остаётся открытой. Контракт: [утверждённый план](../plans/2026-11-09-sp01-text-onboarding.md).
Полная [v0.5](../product/2026-11-08-ai-tutor-experience-v0.5.md) остаётся направлением будущего продукта.

## Реализованный путь

```text
Telegram /start, /onboarding, свободный текст
  → TutorClient → /internal/tutor/start|turn
  → TutorRuntime → native LLM function calls → проверка → TutorStore
  → LLM получает фактический результат → русский ответ
  → Telegram reply + карточка только при изменении встречи
```

С Redis один conversation ID переживает знакомство, запись, отказ, отмену, повторный `/start` и рестарт. In-memory store рестарт не переживает.
Никакой обязательной анкеты или терминального состояния чата нет. Приветствие генерирует модель.
Личность выделена из Kora в `kora_personality.py`; исходный free-call prompt собирается с тем же текстом личности.
Чат использует настроенную модель Kora H, а при отсутствии отдельного Kora model adapter — общий LLM с той же личностью.
Текст любого языка передаётся модели; отвечает Саша по-русски. Голос в обычном чате не запускает STT/TTS.
Явно открытые прежние calls и Vocabulary Builder сохраняют свои обработчики и голосовые возможности.
Их наличие не превращает trial-заглушку в реальный звонок.

## API и данные

Все маршруты защищены существующим `X-Internal-Token` middleware.

- `POST /internal/tutor/start`, `POST /internal/tutor/turn`: `sessionId`, `requestId`, `text`, `receivedAt` (Unix seconds).
  Telegram adapter передаёт исходное `message.date`, не время ответа LLM.
- Ответ: `conversationId`, `reply`, `booking`, `bookingText`, `degraded`, `replyMessageId`, `cardMessageId`.
  `booking` отсутствует на обычных ходах; карточка не повторяется автоматически.
- `POST /internal/tutor/delivery`: подтверждает отправленный reply/card, сохраняет Telegram message ID и версию встречи.
- `GET /internal/learners/{sessionId}/context`: Personal/Learning context нового текстового чата, сводка, trial preference и текущая встреча.
  Маршрут читает только TutorStore. Calls читают LearnerStore (`learner:`); синхронизации нет. Общий профиль отложен, lesson runtime не реализован.
- `GET /internal/tutor/analytics?since=...&until=...`: когорта по времени начала знакомства, конверсия,
  активные записи, переносы, отмены, повторные записи, предложения, ошибки и доставленные requests.
  Чтение learner context само по себе не начинает знакомство. Перенос не увеличивает конверсию.

Namespace: `tutor:v1:{stable sessionId}`. Используется существующий Redis connection; без Redis — локальный memory store.
Personal Memory, Learning Memory, история фактов, история встреч и стадия знакомства — отдельные поля одного learner aggregate.
Операция сохраняет before/result/request identity вместе со встречей и событием в одной Redis transaction.
Redis lock сериализует ход, CAS revision защищает от записи worker после истечения lease.
Дубликат request использует сохранённый ответ. Дубликат tool arguments в том же request использует сохранённый результат.
После падения финального LLM-вызова возвращается достоверный технический fallback по сохранённой встрече.
Поздний повтор старого request показывает актуальную версию встречи, а не устаревшее подтверждение.

История модели: до 40 сообщений, максимум 24 000 символов; профиль выбирает до 20 фактов каждого типа,
приоритет у цели, трудностей, уровня, текущего пояса и фактов по текущей теме. Сводка обновляется через context tool.
Сырые реплики, request/response receipts и сводка подчиняются прежним семи дням хранения; есть периодическая очистка.
Подтверждённые факты с источником, история исправлений и встречи сохраняются независимо от окна диалога.
Самооценка не превращается в измеренный уровень.

## Инструменты и время

Пять настоящих native tools: `get_trial_booking`, `create_trial_booking`, `reschedule_trial_booking`,
`cancel_trial_booking`, `update_learner_context`. JSON Schema строгие; сервер повторно проверяет типы и enum.
Модель не задаёт владельца встречи. Проверяются ID, version, активный статус, источник фактов в текущем сообщении,
точная дата, IANA/UTC offset, будущее время и DST. Есть режимы now/local/shift_minutes/same_time_on_date.
Одна активная запись на learner; перенос сохраняет ID, повторная запись после отмены связывается с предыдущей.
Прошедшая заглушка не становится missed/completed. Пробная встреча — около 15 минут, без слотов занятости AI.

Разбор естественного намерения, городов и относительных дат выполняет модель по исходному времени сообщения.
Код не маршрутизирует беседу по ключевым словам. Семантическую правильность такого разбора нужно проверять живой моделью.
Предел одного model/tool цикла — 55 секунд, до 6 model rounds и до 5 tool calls на round.
Сбой не создаёт фиктивного успешного результата.

## Telegram-карточка

Код сериализует `disabled: {}` у InlineKeyboardButton; основание исходной реализации:
[официальный контракт](https://core.telegram.org/bots/api#inlinekeyboardbutton).
У установленного tgbotapi 36.1.0 этого типа нет. `TutorCardRequest` использует штатный `SimpleRequest`
с точной сериализацией поля `disabled`; URL, callback_data и web_app отсутствуют.
В payload кнопки «Открыть урок» нет действия. Принятие payload и отображение в живом Telegram ещё не проверены.

ID карточки сохраняется в AI store. Перенос и отмена редактируют прежнюю карточку, включая рестарт Ktor.
Если Telegram запрещает редактирование, отправляется актуальная карточка. При отмене клавиатура пуста.
`message is not modified` считается успешным повтором. Ошибка доставки не отменяет сохранённую встречу.
При неопределённом сетевом исходе Telegram send/receipt остаётся стандартное окно возможного дубля сообщения;
повторное выполнение booking tool при этом исключается.

## Удалённый путь и совместимость

Удалены voice onboarding service, begin/checkpoint/results, его clip job поля/DTO, warmup/STT/TTS orchestration,
onboarding mode prompts, callbacks, call gate, автоматическое приглашение и subscription preview.
Старые onboarding state/actions/legacy-invitation endpoints отсутствуют; campaign claim/report больше не доступны. `GET /internal/campaign/legacy-onboarding` сохранён для исторического отчёта (может актуализировать excluded statuses, но не отправляет сообщения).
Старые onboarding followups не отправляются; явные reminder-функции независимых возможностей сохранены.

Общие зависимости вынесены в `LearnerStore`, `LearnerModel`, `review_content.py`, `review_score.py`, `ReviewMessages.kt`.
Calls/reviews/vocabulary/person memory переподключены к ним. Исторические assessment/learner ключи читаются;
миграции, исторические данные и аналитические отчёты не переписаны. Переноса пользователей нет.
Имена существующих `ONBOARDING_REVIEW_MODEL`/fallback env сохранены для совместимости конфигурации обычных reviews.

## Карта кода

| Ответственность | Источник |
| --- | --- |
| Prompt, native tool loop, fallback | [tutor.py](../../ai-service/app/tutor.py) |
| Схемы, intent, время, операции | [tutor_tools.py](../../ai-service/app/tutor_tools.py) |
| Redis, CAS, receipts, retention, analytics | [tutor_store.py](../../ai-service/app/tutor_store.py) |
| Wiring и HTTP routes | [main.py](../../ai-service/app/main.py) |
| Личность | [kora_personality.py](../../ai-service/app/kora_personality.py) |
| Профиль существующих звонков | [learner_store.py](../../ai-service/app/learner_store.py) |
| Ktor DTO и HTTP | [TutorClient.kt](../../cmp/server/src/main/kotlin/com/eliteteam/speakingcoach/ai/TutorClient.kt) |
| Ответ, typing, карточка и receipts | [TutorTelegramChat.kt](../../cmp/server/src/main/kotlin/com/eliteteam/speakingcoach/telegram/TutorTelegramChat.kt) |
| Приоритет команд и независимых функций | [TelegramHandlers.kt](../../cmp/server/src/main/kotlin/com/eliteteam/speakingcoach/telegram/TelegramHandlers.kt) |

Обычный текст попадает к Саше после обработчиков явных команд, активного Vocabulary Builder,
выбора ситуации и ввода времени `/remind`. Эти существующие функции не стали tools репетитора.

## Проверка

[Состояние и журнал доказательств](../status.md): автоматические тесты, частичные модельные пробы,
DEV и открытая приёмка. Эти результаты не являются проверкой текущего PROD.
