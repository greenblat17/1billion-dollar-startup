# Документация Speaky

Speaky — постоянные отношения с AI-репетитором Сашей с характером Kora H.
Telegram служит перепиской; приложение в целевой модели обслуживает согласованные уроки и прогресс.
SP-01 реализует русский текстовый чат и управление trial-записью. Проведение урока пока недоступно.

## Основной маршрут чтения

| Вопрос | Документ |
| --- | --- |
| Для кого продукт, кто такой Саша, какова целевая модель | [Продукт](product.md) |
| Как знакомиться, отвечать и управлять trial без анкеты | [Путь и поведение SP-01](plans/2026-11-09-sp01-text-onboarding.md) |
| Какие компоненты работают и почему устроены так | [Архитектура](../architecture.md), [причины решений](architecture/decisions.md) |
| Какие API, tools, память и Telegram delivery реализованы | [Контракт SP-01](integrations/2026-11-10-sp01-tutor-chat.md) |
| Что реализовано, что отложено и что проверено | [Статус и доказательства](status.md) |
| Как запустить и проверить локально | [Запуск и CI](integrations/2026-09-18-run-and-ci.md) |
| Как обслуживать окружения и сохранять данные | [Эксплуатация и deploy](deploy.md) |

[Исходная v0.5](product/2026-11-08-ai-tutor-experience-v0.5.md) сохранена без изменений как источник решений.
Последующие уточнения SP-01 записаны в пути и контракте. Целевая концепция не равна реализованному выпуску.
`GET /internal/learners/{sessionId}/context` читает новый чат; общего контекста с `learner:` пока нет.
Живая приёмка нового поведения модели и Telegram UX остаётся открытой.

## Сохранённые системы

Это справочники действующих независимых возможностей и исторических данных, а не альтернативные новые продуктовые пути.

- Telegram: [routing и сообщения](integrations/2026-09-18-telegram.md), [calls](integrations/2026-09-30-telegram-calls.md),
  [clip API](integrations/2026-09-18-clip-session-api.md), [profile](integrations/2026-09-29-telegram-profile.md),
  [память звонков](integrations/2026-09-30-personalized-conversation.md), [коррекции](integrations/2026-09-30-spoken-corrections.md).
- Vocabulary Builder: [технический контракт](integrations/vocabulary-builder.md), [метрики](integrations/2026-11-04-vocabulary-builder-analytics.md).
- Voice runtime: [персона, история, провайдеры и review](integrations/voice-runtime.md).
- Напоминания: [practice, soft nudge и streak](integrations/practice-reminders.md).
- CMP-прототип: [UI и макеты](design/2026-09-17-mobile-ui.md), [устройство](architecture/2026-09-17-cmp-client.md),
  [API](integrations/2026-09-18-mobile-api.md), [WebRTC](architecture/2026-09-20-webrtc-client.md).
  Он не является реализованным Mini App из v0.5.
- Эксплуатация: [env](integrations/2026-10-11-env.md), [мониторинг](integrations/2026-10-02-monitoring.md),
  [аудит](architecture/2026-10-05-conversation-audit-logging.md), [ошибки voice](architecture/2026-10-05-voice-error-observability.md),
  [логи провайдеров](integrations/2026-10-14-provider-call-logs.md), [PROD alerts](integrations/2026-10-30-prod-telegram-alerts.md).
- Метрики: [обзор](integrations/2026-10-14-admin-overview.md), [calls dashboard](integrations/2026-10-05-telegram-call-dashboard.md),
  [habit analytics](integrations/2026-10-19-habit-analytics.md), [reminders](integrations/2026-10-20-reminder-effectiveness-dashboard.md),
  [исторический onboarding](integrations/2026-09-30-onboarding-analytics.md).
  Старые voice-метрики не измеряют конверсию нового текстового чата.
- [Совместимость данных](integrations/legacy-compatibility.md): сохранённые данные и границы замены onboarding.

## Как читать остальные материалы

`integrations/` содержит контракты подсистем; `architecture/` — технические решения и эксплуатационные измерения.
`plans/` содержит только текущий SP-01 и краткую границу MVP.
[Проверки подсистем](researches/subsystem-checks.md) сохраняют фактические результаты удалённых планов.
`researches/` и `load/results/` сохраняют доказательства экспериментов, включая неуспешные. Это не обещание качества текущего выпуска.
[Нагрузочный отчёт](<../Нагрузочный тест — не менее 10 RPS, результаты тестирования.md>) относится к голосовому clip pipeline, не SP-01.

Новые решения записываются в соответствующий основной документ. Повторять реализацию и результаты проверок в нескольких планах не нужно.
