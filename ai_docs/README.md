# Документация Speaky

Speaky — постоянные отношения с AI-репетитором Сашей с характером Kora H.
Telegram служит перепиской; приложение в целевой модели обслуживает согласованные уроки и прогресс.

## Основной маршрут чтения

| Вопрос | Документ |
| --- | --- |
| SP-19A: математика Score и хранение попыток | [Контракт и граница fixture-проверки](integrations/learning-score.md) |
| Новый SP-17A: доказательные Observations | [Контракт и handoff](integrations/learning-observations.md), [план](plans/2026-11-16-sp17a-observation-contract.md), [готовность](status.md#новый-sp-17a-observation-contract--core) |
| SP-17B: Trial → Observations | [Контракт](integrations/learning-observations.md#источники-и-handoff-sp-06), [утверждённый план](plans/2026-11-16-sp17b-trial-observation-integration.md), [готовность](status.md#sp-17b-trial--observations) |
| SP-07: Post-Trial Assessment | [Диагностический handoff](integrations/trial-assessment.md), [продукт v5.5](product/2026-11-14-personalized-learning-v5.5.md) |
| SP-12A v5.5: Task Learning Plan / Stage | [Контракт и handoff](integrations/learning-plan.md), [утверждённый план](plans/2026-11-16-sp12a-learning-plan-contract.md) |
| SP-12B v5.5: шаблоны методов и переходы Plan | [Правила и handoff](integrations/learning-plan.md#sp-12b-педагогический-выбор-и-переходы), [утверждённый план](plans/2026-11-16-sp12b-pedagogical-logic.md), [готовность](status.md#sp-12b-v55-педагогическая-логика) |
| SP-13A v5.5: Learning Content Contract | [Контракт и handoff](integrations/learning-content.md), [план](plans/2026-11-16-sp13a-content-contract.md), [примеры](../content-contract/v1/examples.json) |
| SP-13B v5.5: генерация и смысловая проверка | [Реализация и handoff](integrations/learning-content-generation.md) |
| Новый SP-02: учебные каталоги | [Темы по CEFR и навыку](product/2026-11-15-learning-catalog-review.md), [исходный JSON](../learning-catalog/v1/catalog.json), [готовность](status.md#новый-sp-02-v55-учебные-каталоги) |
| Новый SP-04 v5.5: Learning Tasks | [Утверждённый план](plans/2026-11-16-sp04-learning-tasks.md), [контракт](../architecture.md#learning-tasks-sp-04-v55) |
| Новый SP-03 v5.5: учебное состояние | [Утверждённый план](plans/2026-11-15-sp03-student-learning-state.md), [контракт и владение данными](../architecture.md#student-learning-state-sp-03-v55) |
| Актуальная продуктовая модель персонального обучения, уроков и Roadmap | [Продукт v5.5](product/2026-11-14-personalized-learning-v5.5.md) |
| Исходная концепция Саши, интерфейсов и жизненного цикла | [Продукт v0.5 — исторический источник](product/2026-11-08-ai-tutor-experience-v0.5.md) |
| Общий фундамент и исходная матрица проверок | [SP-00](plans/2026-11-10-sp00-foundation.md) |
| Новый SP-06 v5.5: 30-минутный Trial | [Исполнение и handoff](integrations/trial-v55.md), [утверждённый план](plans/2026-11-16-sp06-trial-lesson-v55.md) |
| Целевой пробный урок | [Продукт v5.5, раздел 7](product/2026-11-14-personalized-learning-v5.5.md) |
| Как был реализован прежний пробный урок | [SP-02](plans/2026-11-11-sp02-trial-lesson.md) |
| Исходное UX-ТЗ от Start до записи на пробный | [Первое знакомство](product/2026-11-13-first-acquaintance-ux.md) |
| Как знакомиться, отвечать и управлять trial без анкеты | [Путь и поведение SP-01](plans/2026-11-09-sp01-text-onboarding.md) |
| Целевая педагогика регулярного урока | [Продукт v5.5, раздел 9](product/2026-11-14-personalized-learning-v5.5.md) |
| Как был реализован прежний регулярный урок | [SP-03](plans/2026-11-12-sp03-regular-lessons.md) |
| Как репетитор запоминает, уточняет и использует сведения об ученике | [SP-07](plans/2026-11-12-sp07-memory.md) |
| Как оцениваются навыки и открывается My Progress | [SP-08](plans/2026-11-12-sp08-progress.md) |
| Как создаются персональный маршрут и планы уроков | [SP-06](plans/2026-11-12-sp06-roadmap.md) |
| Как работают подписка, тестовая оплата и доступ | [SP-05](plans/2026-11-12-sp05-subscription.md) |
| Как согласуются расписание, переносы и напоминания | [SP-04](plans/2026-11-12-sp04-scheduling.md) |
| Какие компоненты работают и почему устроены так | [Реализованная архитектура и причины решений](../architecture.md) |
| Что реализовано, что отложено и что проверено | [Статус и доказательства](status.md) |
| Визуальный стиль Mini App и макет звонка | [Стайлгайд и HTML-макеты](design/README.md) |
| Как запустить и проверить локально | [Запуск и CI](integrations/operations.md#run-locally-env-ci) |
| Как обслуживать окружения и сохранять данные | [Эксплуатация и deploy](integrations/operations.md#выкладка-и-восстановление) |

[Продуктовая v5.5](product/2026-11-14-personalized-learning-v5.5.md) — актуальный источник целевого педагогического поведения. Это концепция, а не подтверждение реализации или поставки.
[Продуктовая v0.5](product/2026-11-08-ai-tutor-experience-v0.5.md) сохранена без изменений для происхождения решений. При расхождении с v5.5 действует v5.5; ранние планы SP-00–SP-08 описывают согласованный тогда объём, а не меняют этот приоритет.
UX-ТЗ первого знакомства сохранено с согласованной правкой вопроса о цели. Точный текст четырёх приветствий и технические уточнения закреплены в SP-01.
Поздние уточнения диалога в SP-01 имеют приоритет над ранними сценариями знакомства, если они не противоречат v5.5; готовность функций описывает только status.md.
[Исходная архитектура v1](architecture/speaky-mvp-high-level-architecture-v1.md) — целевой контракт MVP, не описание текущего runtime.
Планы SP-00–SP-08 сохраняют согласованные решения и границы каждого этапа.
Ограничения ранних этапов не отменяют последующие реализации; текущая готовность — только в [статусе](status.md).

## Шесть технических справочников

| Документ | Содержание |
| --- | --- |
| [Чат и Telegram](integrations/tutor-chat.md) | SP-01 tools, trial, routing, delivery и совместимость |
| [Голос и review (legacy)](integrations/voice-and-reviews.md) | Сохранённые Clip API, calls, коррекции и оценки; активный Realtime — в архитектуре и SP-02/03 |
| [Практика (legacy)](integrations/practice.md) | Vocabulary Builder, reminders, streak и HTTP-операции |
| [Аналитика (legacy)](integrations/analytics.md) | События, dashboards, знаменатели, когорты и стоимость |
| [Эксплуатация](integrations/operations.md) | Запуск, CI, env, мониторинг, provider logs и alerts |
| [CMP API (legacy)](integrations/mobile-api.md) | Auth, Home, WebRTC, sessions и review существующего приложения |

Внутри каждого файла есть оглавление. Старые voice-метрики не измеряют конверсию нового чата.
CMP остаётся отдельным прототипом: [клиент, WebRTC и API](integrations/mobile-api.md).
Порядок выкладки и восстановления — в [deploy](integrations/operations.md#выкладка-и-восстановление).

## Как читать остальные материалы

`architecture.md` — единственное описание реализованной архитектуры и причин решений.
`architecture/` содержит исходный целевой контракт MVP. Правила агента находятся только в `AGENTS.md`.
`integrations/` содержит технические контракты и эксплуатацию; legacy-справочники не описывают активные маршруты SP-00.
`plans/` содержит объём SP-00–SP-08, контракты и границы проверок.
`status.md` — единственная краткая сводка реализации и поставки.
Значимые результаты прежних проверок и ограничения моделей находятся в технических контрактах соответствующих подсистем.

Новые решения записываются в соответствующий основной документ. Повторять реализацию и результаты проверок в нескольких планах не нужно.
