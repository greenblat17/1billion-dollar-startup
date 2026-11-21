# Документация Speaky

В Telegram ученик переписывается с Сашей, персональным AI-репетитором с характером Kora H. В целевой модели приложение отвечает за уроки и прогресс.

## Основной маршрут чтения

| Вопрос | Где искать |
| --- | --- |
| Как должен работать продукт? | [Продукт v5.5](product/2026-11-14-personalized-learning-v5.5.md). Для уроков см. [пробный урок](product/2026-11-14-personalized-learning-v5.5.md#пробный-урок-trial-lesson-и-оценка-topics) и [регулярный урок](product/2026-11-14-personalized-learning-v5.5.md#9-lesson-execution--согласованная-модель-поведения-ai-репетитора). |
| Какие компоненты работают и почему они так устроены? | [Архитектура](../architecture.md) |
| Что записывают логи и как связать события? | [Запись логов Speaky](architecture/runtime-logging.md) |
| Что реализовано, проверено и доставлено? | [Состояние проекта](status.md), включая [пробный звонок SP-24](status.md#sp-24--пробный-звонок) |
| Как выглядит Mini App? | [Стайлгайд и макеты](design/README.md) |

## Контракты персонального обучения v5.5

| Этап | Источники |
| --- | --- |
| SP-02. Учебные каталоги | [Темы по CEFR и навыку](product/2026-11-15-learning-catalog-review.md), [исходный JSON](../learning-catalog/v1/catalog.json), [готовность](status.md#новый-sp-02-v55-учебные-каталоги) |
| SP-03. Учебное состояние | [Владение данными](../architecture.md#student-learning-state-sp-03-v55), [утверждённый план](plans/2026-11-15-sp03-student-learning-state.md) |
| SP-04. Learning Tasks | [Контракт](../architecture.md#learning-tasks-sp-04-v55), [утверждённый план](plans/2026-11-16-sp04-learning-tasks.md) |
| SP-06. 30-минутный Trial | [Исполнение](integrations/trial-v55.md), [утверждённый план](plans/2026-11-16-sp06-trial-lesson-v55.md) |
| SP-07. Post-Trial Assessment | [Диагностика после пробного урока](integrations/trial-assessment.md) |
| SP-08. Первая Roadmap | [Контракт и границы проверки](plans/2026-11-17-sp08-roadmap-handoff.md) |
| SP-12A. Task Learning Plan и Stage | [Контракт](integrations/learning-plan.md), [утверждённый план](plans/2026-11-16-sp12a-learning-plan-contract.md) |
| SP-12B. Шаблоны методов и переходы | [Правила](integrations/learning-plan.md#sp-12b-педагогический-выбор-и-переходы), [утверждённый план](plans/2026-11-16-sp12b-pedagogical-logic.md), [готовность](status.md#sp-12b-v55-педагогическая-логика) |
| SP-13A и SP-13B. Learning Content | [Контракт](integrations/learning-content.md), [план SP-13A](plans/2026-11-16-sp13a-content-contract.md), [примеры](../content-contract/v1/examples.json), [генерация и смысловая проверка](integrations/learning-content-generation.md) |
| SP-17A и SP-17C. Observations и результаты Teaching Actions | [Контракт Observations](integrations/learning-observations.md), [план SP-17A](plans/2026-11-16-sp17a-observation-contract.md), [готовность SP-17A](status.md#новый-sp-17a-observation-contract--core) |
| SP-17B. Observations после Trial | [Источники](integrations/learning-observations.md#источники-и-handoff-sp-06), [утверждённый план](plans/2026-11-16-sp17b-trial-observation-integration.md), [готовность](status.md#sp-17b-trial--observations) |
| SP-19A и SP-19B. Stage Score | [Расчёт по Observations](integrations/learning-score.md) |
| SP-20. Post-Lesson Review | [Контракт](integrations/post-lesson-review.md), [карта задач после урока](integrations/lesson-results.md), [готовность волны 9](status.md#совместная-интеграция-волны-9) |

Границы совместной проверки описаны в отчётах по волнам:

- [Волна 5](status.md#совместная-интеграция-волны-5) охватывает первую Roadmap (SP-08) и Teaching Actions (SP-14/15), включая границу с SP-16/17C.
- [Волна 6](status.md#совместная-интеграция-волны-6) охватывает приоритеты и сроки Roadmap (SP-09/10) и результаты Teaching Actions (SP-17C).
- [Волна 7](status.md#совместная-интеграция-волны-7) охватывает доставку Roadmap в Telegram (SP-11A), предложения новых Learning Tasks (SP-18) и Stage Score (SP-19A/B).

<a id="шесть-технических-справочников"></a>

## Эксплуатация и приёмка

| Задача | Справочник |
| --- | --- |
| Запустить, развернуть или восстановить сервис | [Эксплуатация и мониторинг](integrations/operations.md), [локальный запуск и CI](integrations/operations.md#run-locally-env-ci), [выкладка и восстановление](integrations/operations.md#выкладка-и-восстановление) |
| Разобрать текстовый диалог | [Чат и Telegram](integrations/tutor-chat.md), [изолированная агентская приёмка](integrations/agent-acceptance.md) |
| Проверить результаты на DEV для волн 1–9 | [Матрица AC](acceptance/dev-waves-1-9-matrix.md), [текстовые диалоги](acceptance/runs/text-ac-results.md), [регулярный урок](acceptance/runs/regular-lesson-ac-2026-11-17.md), [оставшиеся проверки](acceptance/remaining-ac-execution.md) |
| Выбрать модель Codex для работы над репозиторием | [Выбор Sol или Astra](integrations/codex-models.md) |

## Сохранённые системы

| Система | Что описывает справочник |
| --- | --- |
| [Голос и review](integrations/voice-and-reviews.md) | Clip API, звонки, коррекции и оценки |
| [Практика](integrations/practice.md) | Vocabulary Builder, напоминания, streak и HTTP-операции |
| [Аналитика](integrations/analytics.md) | События, знаменатели, когорты и стоимость. Старые голосовые метрики относятся к прежнему сценарию. |
| [CMP API](integrations/mobile-api.md) | Авторизация, Home, WebRTC, сессии и review отдельного прототипа CMP |

<a id="как-читать-остальные-материалы"></a>

## Ранние решения

[Продукт v0.5](product/2026-11-08-ai-tutor-experience-v0.5.md) и [исходная архитектура MVP](architecture/speaky-mvp-high-level-architecture-v1.md) фиксируют первоначальный замысел. Планы ниже описывают объём, согласованный на каждом этапе. При расхождении целевое продуктовое поведение задаёт v5.5.

- [SP-00. Фундамент и матрица проверок](plans/2026-11-10-sp00-foundation.md).
- [SP-01. Знакомство в Telegram](plans/2026-11-09-sp01-text-onboarding.md) уточняет [исходное UX-ТЗ](product/2026-11-13-first-acquaintance-ux.md), включая тексты четырёх приветствий. В UX-ТЗ сохранена согласованная правка вопроса о цели. В пределах v5.5 для диалога действуют поздние уточнения SP-01.
- [SP-02. Пробный урок](plans/2026-11-11-sp02-trial-lesson.md) и [SP-03. Регулярный урок](plans/2026-11-12-sp03-regular-lessons.md).
- [SP-04. Расписание, переносы и напоминания](plans/2026-11-12-sp04-scheduling.md) и [SP-05. Подписка, тестовая оплата и доступ](plans/2026-11-12-sp05-subscription.md).
- [SP-06. Roadmap и планы уроков](plans/2026-11-12-sp06-roadmap.md), [SP-07. Память об ученике](plans/2026-11-12-sp07-memory.md) и [SP-08. Оценка навыков и My Progress](plans/2026-11-12-sp08-progress.md).

Правила работы агента находятся в [AGENTS.md](../AGENTS.md). Новые решения записывайте в основной документ подсистемы, результаты проверок и поставки обновляйте в сводке состояния проекта.
