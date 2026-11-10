# Коррекции речи в сохранённых calls

Текстовый репетитор использует отдельный prompt. Этот контракт относится к clip pipeline и post-call review.
Источник: `ai-service/app/correction_policy.py`, `llm.py`, `pipeline.py` и `review_content.py`.

## Live correction

Модель ищет явные синтаксические и лексические ошибки во всей распознанной реплике.
Новые кандидаты имеют `kind=grammar`, `vocabularyCategory=null`. Нормальная простая речь не является ошибкой.
Повторы, самокоррекция, незаконченная мысль, пунктуация STT и сомнительное распознавание не должны давать карточку.
Приоритет — не исправить правильную речь; полнота поиска не гарантируется.

Модель возвращает скопированный `context`, локальные `error` и `replacement`, причину и decision flags.
Код требует high confidence, definitely_wrong/worth_showing/understandable_alone=true,
is_spoken_language_artifact/is_asr_uncertain=false. Контекст содержит не более 30 слов;
исправление должно однозначно совпадать по целым словам. Пересечения, дубли и неподтверждённые spans отбрасываются.
Проверка учитывает апострофы и защищает от удаления модального/основного глагола.
Flags — суждение модели, а не независимая проверка аудио или смысла.

Публичный формат: `corrections: [{wrong, better, kind, explanation?}]`.
Legacy `notes: ["wrong|||better"]` сохраняется для клиентов.
Русское объяснение необязательно: одна строка/предложение, до 160 символов, без общего шаблона.
Telegram показывает не более одной карточки за ход; независимые принятые ошибки сохраняются для review.
В карточке выделяется локальная правка внутри контекста. Английские фрагменты объяснения выделяются monospace.

## Ограничения и сбои

Notes генерируются параллельно ответу; бюджет — 1200 tokens, общий deadline — 10 секунд.
Для notes задан reasoning.effort=none. Внутри deadline допустимы максимум две provider attempts;
валидный пустой ответ и safety-filtered candidate не вызывают повтор.
SDK retries отключены; явный controller отвечает за retry и backoff.
Сбой notes не блокирует готовый голосовой ответ. Deadline или неполный JSON могут потерять все кандидаты хода.

`metrics:corrections:YYYY-MM-DD` считает один конечный outcome на запрос по московскому дню,
включая elapsedMs и secondAttempts. `shown` означает прохождение AI-фильтра, не доставку Telegram-карточки.
Тексты реплик не входят в эти метрики. `/internal/metrics` и `/admin/metrics` показывают агрегаты.

## Финальный review

Сохранённые ошибки проходят отдельную проверку ID группами до пяти, до трёх групп параллельно,
с общим deadline 10 секунд. Успешные группы сохраняются при сбое другой группы.
Выбираются до пяти Grammar examples; объяснения берутся из сохранённого хода.
Legacy `word`/`natural` должны пройти ту же проверку; старые alternatives не становятся ошибками.
Готовые cached reports не регенерируются.

Vocabulary upgrades ищутся отдельно по всему диалогу и не снижают score:
[актуальный workflow](2026-10-21-vocabulary-assessment-workflow.md).
Почему сохранены отдельные поля review: [voice runtime](voice-runtime.md).

## Доказательства

Unit tests проверяют parser и gates, но не качество решений модели.
Синтетические пробы и их ошибки сохранены в [результатах подсистем](../researches/subsystem-checks.md),
[deadline experiment](../researches/2026-10-02-correction-timeout-experiment.md) и
[PROD audit](../researches/2026-10-20-production-correction-audit.md).
`evals/spoken_corrections.json` содержит синтетические cases; они не измеряют ошибки распознавания реального аудио.
Новых платных прогонов при обновлении документации не было.
