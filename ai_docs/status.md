# Состояние Speaky

Срез документации: 2026-11-13. База функционального кода: `dev` **fce9b627ffc936eaa2107d72ac452985f3612e2a**.
Реализация, workflow поставки и живая приёмка — разные доказательства.

## Реализовано в коде

| Область | Доступное поведение |
| --- | --- |
| SP-00 | PostgreSQL foundation, единый профиль/контекст, scopes, версии, receipts, jobs и удаление; legacy изолирован |
| SP-01 | Постоянный Telegram-диалог, native tools, trial create/read/reschedule/cancel/rebook; жирный текст и code через entities |
| SP-02 | Отдельный Mini App, Realtime/Marin, trial, reconnect, playback/truncation, S3-архив и Telegram follow-up |
| SP-03/04 | Подготовка и проведение regular 15–60 минут, согласование расписания, переносы/отмены, DST-уточнения, reminders и no-show |
| SP-05 | Opt-in mock checkout, тестовое продление, отмена автопродления, entitlement gate для regular; trial бесплатен |
| SP-06 | Roadmap после анализа, адаптация по источникам, подготовка следующего урока, явные terminal failures |
| SP-07 | Общая память text/voice/learning, выборка, запреты упоминания, спорные наблюдения, забывание с отзывом контекста |
| SP-08 | Качественный прогресс по свидетельствам и My Progress без звонка, микрофона или подписки |

Контракты и исходники: [архитектура](../architecture.md), [карта планов и справочников](README.md).
CMP Android/iOS/Desktop остаётся отдельным прототипом; его API не подключён к shipping runtime.

## Поставка и проверки

- GitHub проверен read-only 2026-11-13. Последний **Redeploy DEV** —
  [43295801220](https://github.com/greenblat17/1billion-dollar-startup/actions/runs/43295801220),
  `4a08369daf6008dd42945a8140a03d6e84960ca9`: backend, AI, Mini App и readiness — success.
  Он включает SP-05/06/07 и Telegram entities. Это workflow evidence, не проверка текущего сервера.
- SP-08 входит в `dev` (`fce9b62`), но более новая поставка DEV не найдена.
  Текущий PROD этим срезом не проверялся.
- На `fce9b62` AI service, CMP и Mini App checks — success. **Foundation integration — failure**:
  [43304873405](https://github.com/greenblat17/1billion-dollar-startup/actions/runs/43304873405),
  57 тестов, один сбой `realBackupRestoreReplaysDeletionLedgerBeforeServingContext`
  (`IllegalStateException`, `FoundationIntegrationTest.kt:164`). Причина здесь не установлена.
- Последний сохранённый локальный результат SP-08: 652 Python, 288 Kotlin unit,
  57 PostgreSQL integration, 27 HTTP integration, 7 Mini App — passed; fatJar/detekt/build прошли.
  Первый Kotlin-прогон имел таймауты, последовательный повтор прошёл. Python имеет dependency warning.
  Это прежние результаты, в документационной задаче runtime-тесты не повторялись.
  HTTP использовал настоящий Python → Ktor → PostgreSQL; модели, Realtime/S3 и доставки — подставные.
  UI проверялся через подставной DOM, без визуальной приёмки.

## Открытые ограничения и приёмка

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
в [operations](integrations/operations.md); новый deploy и реальные сообщения этой задачей не выполнялись.
