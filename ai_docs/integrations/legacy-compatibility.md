# Совместимость сохранённых систем

SP-01 заменил voice onboarding постоянным текстовым чатом. [Текущий контракт](2026-11-10-sp01-tutor-chat.md)
и [статус](../status.md) важнее ранних продуктовых планов.

## Что удалено из пользовательского пути

Нет приглашения Let’s chat, голосовых порогов 30/120 секунд, onboarding begin/checkpoint/results,
цепочки выбора daily goal после знакомства, старого call gate, campaign claim/report и subscription preview.
`/start` и `/onboarding` теперь продолжают один tutor conversation, а не сбрасывают attempt.
Урок не запускается; старые цены превью не определяют будущую подписку.

`GET /internal/campaign/legacy-onboarding` оставлен для просмотра статуса старой кампании.
Внутри `campaign_status` может помечать ещё не отправленных получателей `skipped_completed` по старым assessment.
Это не endpoint отправки: campaign runner в Ktor равен `null`. Исторические `pending`/`uncertain` не доказывают доставку.

## Что сохраняется

- `LearnerStore`, `LearnerModel`, `review_content.py`, `review_score.py` обслуживают calls, reviews и vocabulary.
- Исторические `onboarding:*`, `assessment:*`, `practice-goal:*`, `learner:*`, campaign и PostgreSQL-таблицы читаются для совместимости.
  Применённые Flyway-миграции не переписываются. Удаление документов не удаляет данные.
- Оценки старых reviews используют CEFR/band/position/shade; это не пять показателей нового продукта.
  [Profile](2026-09-29-telegram-profile.md), [коррекции](2026-09-30-spoken-corrections.md) и
  [workflow оценки](2026-10-21-vocabulary-assessment-workflow.md) описывают сохранившуюся часть.
- В `Application.kt` followup stages старого onboarding получают `retired`, soft nudge для onboarding — `false`.
  Ветка `practice`, настроенный `/remind` и soft nudge открытого call остаются.
  SP-01 не добавляет напоминания о trial. Нельзя считать весь старый scheduler отключённым.
- [Onboarding analytics](2026-09-30-onboarding-analytics.md) и отчёты reminders сохраняют старые когорты,
  стадии и delivery outcomes. Они не становятся аналитикой Саши автоматически.
- `ONBOARDING_REVIEW_MODEL` и fallback env сохраняют имена для review-совместимости.
- Прототип CMP использует собственный app API и WebRTC. Его макеты Releva сохраняются как материалы прототипа,
  а не как дизайн будущего Mini App.

## Тайминг сохранённой practice-ветки

`FollowupTiming.kt` группирует входящие события в visits с интервалом 30 минут и использует историю за 30 дней.
Предпочтительное UTC-время — медиана наиболее плотного двухчасового окна; при равенстве учитывается свежесть визита.
Одиночный визит даёт собственное время. Требуются 24 часа неактивности, окно догоняющей отправки — два часа.
V17 хранит schedule, версию политики, `scheduled_for` и dirty/recompute marker.
Daily claim остаётся не более одного на московский день; неизвестная доставка не считается подтверждённой.
Это сохранённая механика прежней practice-ветки, не разрешение присылать приглашения trial.
Текущие правила practice: [practice reminders](practice-reminders.md),
[метрики](2026-10-20-reminder-effectiveness-dashboard.md).
