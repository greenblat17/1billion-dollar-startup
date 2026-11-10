# Аналитика и определения метрик

Контракты базовой поставки SP-01 и сохранённого legacy-кода. Новый фундамент SP-00 использует PostgreSQL;
legacy сохраняется отдельно, без переноса его Redis-хранилищ и без обязательного подключения старых функций к новому runtime.
Готовность изоляции и переключения текущего чата проверяется по [статусу](../status.md).
Описание legacy API ниже не означает, что эти маршруты доступны в новом runtime.

- [Главная страница аналитики `/admin/metrics`](#главная-страница-аналитики-adminmetrics)
- [Onboarding analytics](#onboarding-analytics)
- [Дашборд практических звонков Telegram](#дашборд-практических-звонков-telegram)
- [Регулярность Speaky](#регулярность-speaky)
- [Аналитика напоминаний в админке](#аналитика-напоминаний-в-админке)
- [Reminder effectiveness dashboard](#reminder-effectiveness-dashboard)
- [Vocabulary Builder analytics](#vocabulary-builder-analytics)
- [Стоимость успешного голосового обмена](#стоимость-успешного-голосового-обмена)

## Главная страница аналитики `/admin/metrics`

Статус: реализовано в коде; проверка PROD после выката проводится отдельно.

`/admin/metrics` — защищённая главная страница. Прежняя сводка доступна по `/admin/metrics/details`; остальные административные страницы сохраняют адреса и вход через `METRICS_PASSWORD`. Период — от 1 до 30 московских суток, включая границы; по умолчанию последние семь завершённых дней (после добавления [технический контракт](analytics.md#регулярность-speaky)); текущий день можно выбрать явно. CMP строит HTML на сервере, обращаясь к AI за Redis-агрегатами и к PostgreSQL за подробными событиями. Браузер не обращается к хранилищам напрямую. Loki в расчётах не участвует.

Под навигацией страницы расположена ссылка на [PROD Grafana](https://89.19.214.50:8443/d/speaky-v2/speaky); она открывается в новой вкладке.

Временные графики показывают ось Y с нулём, делениями и числовыми подписями в единицах графика; ось X — даты с промежуточными отметками (до шести на длинном периоде). При отсутствии измерений линия прерывается. На узком экране график прокручивается по горизонтали, чтобы подписи не слипались.

Итоговые показатели сохранённой истории сгруппированы на странице в три блока: «Аудитория», «Возврат пользователей» и «Использование AI». Накопленное число пользователей с завершённым голосовым ходом выделено как главное в блоке аудитории. Stickiness и retention стоят рядом, а технический объём AI отделён от продуктовой аудитории. Группы используют общую поверхность и разделители вместо отдельной рамки вокруг каждого числа; подписи и определения остаются под показателями.

### Основной блок: сохранённая история Redis

`metrics:day:YYYY-MM-DD`, `metrics:dau:YYYY-MM-DD` и `metrics:funnel:user:*` начали заполняться на PROD 24 сентября 2026 года и продолжают заполняться после 6 октября. Определение каждой линии одинаково на 3, 8, 14 октября и в любой другой выбранный день сохранённой истории. Это основной блок страницы; данные до 6 октября не вынесены в отдельную «старую» таблицу. Дневные ключи старого учёта и даты в пользовательской воронке не имеют TTL. Доступный диапазон подтверждается существующими ключами; даты раньше первой записи не показываются нулями.

| Показатель | Расчёт и граница |
| --- | --- |
| Всего Telegram-чатов на конец дня / новые чаты | Уникальный Telegram chat ID и его минимальный `first_day` из `metrics:funnel:user:*`. Чаты без даты не входят в ряд. ID группового чата с разными ветками объединяются по базовому chat ID. |
| Первый `/start`, первый голос, третий обмен | Минимальные `start_day`, `activated_day`, `engaged_day` для каждого чата из той же воронки. Первый голос не равен числу всех входящих голосовых. Третий обмен — порог `ENGAGED_EXCHANGES=3` старой воронки. |
| Завершили ход AI за день | Уникальные Telegram-чаты из `metrics:dau:YYYY-MM-DD`, где за день завершился хотя бы один ход AI. Историческое имя ключа — DAU, но на странице это не называется активными пользователями: приславшие голос без завершённого хода сюда не попадают. |
| Завершили ход AI за выбранный период | Объединение этих дневных наборов, а не сумма значений по дням. |
| Завершили голосовой ход AI · всего | Число уникальных Telegram ID в объединении всех сохранённых `metrics:dau:*` по выбранный день включительно, даже если выбранный период начался позже. Это накопленная оценка пользователей, дошедших до обработки голосового; AI фиксирует ход до доставки ответа в Telegram. Точное число пользователей с доставленным ответом за всю историю из этого счётчика не следует. |
| Завершили ход AI за 7 дней | Объединение дневных наборов за день графика и шесть предыдущих календарных дней. Первые шесть дней доступной истории не показываются, поскольку окно неполное. |
| Stickiness 7 / 30 дней | Завершившие ход AI за день / уникальные с завершённым ходом за последние 7 или 30 календарных суток включительно. Считается по `metrics:dau:*`; до полного окна или при нулевом знаменателе показывается «—». 30-дневное окно впервые доступно через 29 дней после первого сохранённого набора. |
| Retention D1 / D7 / D30 | Когорта — Telegram-чаты с первым голосовым (`activated_day` в `metrics:funnel:user:*`) в выбранном периоде. Числитель — чаты когорты в `metrics:dau:*` ровно через 1, 7 или 30 дней после активации. В знаменатель каждого D входят только пользователи, у которых целевой день уже завершился по Москве. Неполный текущий день исключён. Значение «—» означает, что зрелой когорты ещё нет; число участников знаменателя показано на карточке. Это возврат к завершённому ходу AI, а не любое открытие бота. |
| Вернувшиеся после завершённого хода | Чаты из дневного набора завершённых ходов, которые были в хотя бы одном более раннем сохранённом дневном наборе. До начала выбранного периода тоже ищем историю. |
| Завершённые ходы AI | Поле `turns` из `metrics:day:*`, все клиентские платформы. Ход фиксируется до Telegram-доставки. |
| Токены LLM | Две отдельные итоговые цифры: входные `prompt_tokens` и выходные `completion_tokens` из того же дневного ключа, все платформы и назначения вызовов. График и таблица по дням тоже разделяют вход и выход. |
| Длительность STT | `stt_ms`: длительность обработанного аудио, не ожидание провайдера; график и итог показаны в минутах. |
| Символы TTS | `tts_chars`: длина текста, отправленного на озвучивание. |
| Ходы / активную AI-сессию | Сумма `turns` за выбранный период / число уникальных сессий всех платформ в объединении `metrics:dau` за тот же период. |
| Входные / выходные токены на ход | Каждая сумма токенов отдельно делится на число завершённых ходов за выбранный период. В токены входят и дополнительные LLM-вызовы внутри хода. Показатель относится ко всем платформам; при нуле ходов показывается «—». |
| Источники | Текущий атрибут `source` из пользовательской воронки; по каждому источнику считаются чаты с `first_day` и `activated_day` в выбранном периоде. Это срез по текущей сохранённой атрибуции. |

В интерфейсе Telegram chat ID называется «пользователь»: в личном диалоге это один человек; групповой чат считается одним ID. Главная карточка «Всего пользователей на конец периода» использует только ID с известным `first_day` к выбранной дате. Отдельное поле API `totalChats` показывает все известные ID, в том числе без даты первого обращения; расхождение отражено в `chatGrowthUnattributed`. Накопленное число пользователей с завершённым голосовым ходом показано рядом с общим числом пользователей, на графике роста и в таблице по дням. Неполный первый день сбора показан как записан, без попытки досчитать пропущенные часы. Метрики до установки фильтра нагрузочных тестов могут включать такой трафик, если его записал старый счётчик.

Счётчик Grafana `speaking_telegram_voice_users` использует ту же нормализацию Telegram chat ID. На PROD 15 октября старый и новый форматы session ID содержали две записи одного чата: сырой счётчик показывал 81, а уникальных чатов было 80. Суммировать записи по ключам session ID без нормализации для числа людей нельзя.

### Детальный блок нового учёта

С 6 октября есть отдельные записи `metrics:v2:day:YYYY-MM-DD:telegram` и события PostgreSQL `interaction_events`, `telegram_voice_attempts`. **Активный пользователь** в нижнем блоке — уникальный Telegram ID, отправивший хотя бы одно голосовое за выбранный день или период; без успешного ответа он всё равно активен. Число отправителей за период считается по уникальным ID за весь интервал, а не суммой дневных значений. До появления `telegram_voice_attempts` дневных отправителей восстановить нельзя, поэтому исторический ряд завершённых ходов AI не подставляется вместо них. В нижнем блоке также доступны число входящих голосовых, доставленные голосовые обмены, вызовы LLM/STT/TTS, исходящие попытки Telegram API, ошибки и известная стоимость LLM на обмен. Определения и ограничения остаются следующими:

- Входящий голос — уникальная строка `telegram_voice_attempts`. Успешный обмен — `outcome='delivered'` и готовая непустая транскрипция с тем же `attempt_id`. Отсутствие записи аудита может занизить обмены.
- «Входящие сообщения» — строки `interaction_events` с `direction='incoming'`, `status='received'`, непустым `chat_id` и типом `voice`, `text` или `photo`; повторное получение одного Telegram update не удваивает строку благодаря `event_id`. Команды входят как текст. `callback` (нажатие кнопки) показывается отдельно на том же графике и в таблице. Доступно только с начала PostgreSQL-журнала, предыдущие `turns` из Redis не подменяют число сообщений.
- Вызовы моделей — успешно учтённые ответы Telegram в Redis; не все ошибки и внутренние повторы SDK восстановимы. Telegram API — записанные `send*`, `edit*`, `deleteMessage`, `setMessageReaction` с `attempt_id`; фоновые рассылки и некоторые методы не входят.
- «Вызовов / активный чат» = все учтённые LLM + STT + TTS + Telegram API / уникальные отправители голосовых в PostgreSQL за период. Вызовы моделей не привязаны в старых агрегатах к этим конкретным отправителям, поэтому отношение остаётся оценкой и не заменяет подтверждённую конкурсную метрику. Если источники не покрывают весь период, значение скрыто.
- «LLM-cost / обмен» = известная сумма `usage.cost` LLM-попыток голосового сценария / число успешных обменов. Покрытие цены показано рядом. Неизвестная цена — пропуск, не ноль; показатель служит нижней границей.
- Ошибка голосового пути — терминальный исход попытки с `eligible=true`, отличный от `delivered` и `not_eligible`.

Первые дни нового учёта могут быть неполными. Итоговые отношения показываются только для периода, начинающегося после первого дня соответствующего источника. При выборе дней только до начала нового учёта нижний блок скрыт; основной Redis-блок остаётся доступным. PostgreSQL хранит исходные события 30 дней; дневные Redis-записи нового учёта имеют TTL 30 дней. Ограничение периода выбора в 30 дней относится к длине интервала, а не к возрасту дат. Prometheus хранит отдельные скрейпы до 185 дней и не используется для расчёта этой страницы.

На проверенном PROD таблицы `interaction_events`, `telegram_voice_attempts`, `onboarding_entries`, `onboarding_attempts`, `onboarding_voices` и `telegram_call_events` не содержали строк до 6 октября. Поэтому точное число всех входящих голосовых, доставленных ответов и отдельных вызовов компонентов до этой даты из имеющихся записей не получается.

Дневные `prompt_tokens` и `completion_tokens` дают сумму токенов за сутки, но не поминутный TPM. Отдельные LLM-события в Redis сохраняются около двух минут (`metrics:llm:events`); время генерации OpenRouter в них не записывается. [OpenRouter Analytics API](https://openrouter.ai/skills/openrouter-analytics-query) возвращает поминутные данные за исторические даты, если запрашивать их окнами не длиннее трёх часов. Запрос с измерением `generation_id` и `avg_generation_time` даёт токены и время генерации отдельных обращений; эта подробность ограничена 31-дневным интервалом запроса. Данные доступны только в аккаунте, которому принадлежит ключ управления. Вызовы через других провайдеров и тесты, прошедшие через тот же рабочий API-ключ, автоматически не отделяются.

### Импорт исторических TPM/TPS OpenRouter

`load/openrouter_llm_rates.py` импортирует только выбранные LLM-модели (`--model` и/или `--model-prefix`). Ключ управления берётся из `OPENROUTER_MANAGEMENT_KEY` или из приватного `--env-file`; ключ и ID отдельных генераций в итоговый JSON не пишутся. Скрипт сначала находит часы с обращениями, затем запрашивает отдельные генерации за каждый такой час; при ограничении в 10 000 строк делит час на меньшие части. `TPM` для каждой календарной минуты UTC — `tokens_prompt + tokens_completion`. `TPS` — сумма выходных токенов вызовов с известным временем генерации / сумма их `generation_time` в секундах. Это соответствует отношению счётчиков генерации в текущем Grafana-графике, но OpenRouter охватывает только выбранные модели и аккаунт. Отсутствующее время не считается нулём.

Пример для тестового аккаунта (файл с ключом вне репозитория):

```bash
python3 load/openrouter_llm_rates.py \
  --from-utc 2026-10-14T05:00:00Z --to-utc 2026-10-14T08:00:00Z \
  --model-prefix openai/gpt- --scope test \
  --env-file /private/path/openrouter.env \
  --output /private/path/openrouter-llm-rates.json
```

CMP по умолчанию читает агрегаты из `/opt/speaking-coach/analytics/openrouter-llm-rates.json` и показывает в разделе графиков `/admin/metrics`, после графиков аудитории, два графика: **TPM (токенов/мин)** и **TPS (токенов/с)**. Точки строятся по минутным группам вызовов. Ось X использует фактическое время по Москве, а минуты без вызовов и минуты без известного времени генерации для TPS прерывают линию. По умолчанию показан последний активный день архива; отдельные элементы управления позволяют выбрать день, окно в 1 или 7 дней либо весь архив. Ось Y пересчитывается по значениям выбранного окна. Выбор окна не меняет фильтр дат остальных метрик. Таблица отдельно показывает дневные максимумы и длительность покрытого интервала; неполный день нельзя интерпретировать как полный. `OPENROUTER_RATES_FILE` позволяет переопределить путь. Над графиками указан тип аккаунта (`ТЕСТОВЫЙ` либо рабочий), даты и список моделей. При отсутствии файла блок не появляется. Для обновления PROD-архива нужен ключ управления того же OpenRouter-аккаунта, где работает PROD AI; после проверки моделей скрипт запускается с `--scope production` на интервал до первой записи Prometheus. Итоговый JSON копируется на CMP-хост в `/opt/speaking-coach/analytics/`, который монтируется в контейнер. Простая замена значения ключа без повторного импорта не меняет уже сохранённые числа.

Проверка на доступном тестовом аккаунте 14 октября 2026 года, 05:00–08:00 UTC: 23 384 генерации LLM, 19 908 714 входных и 1 507 711 выходных токенов; суммы совпали с агрегатным запросом OpenRouter. Пик минутного TPM — 1 788 909, пик минутного TPS — 50,476. Это тестовая нагрузка, а не история пользователей PROD до появления Prometheus.

Проверка именно интервала **до первых данных Prometheus** (23 сентября 21:00 UTC — 6 октября 08:50 UTC) на доступном тестовом аккаунте дала 2 267 LLM-генераций за 136 активных минут 30 сентября — 2 октября, 3 113 789 входных и 251 511 выходных токенов. Суммы также совпали с отдельным агрегатным запросом OpenRouter. Пиковые минутные значения в этом тестовом аккаунте: 139 984 TPM и 190,587 TPS. Эти цифры нельзя подписывать как показатели PROD: ключ управления принадлежит другому аккаунту.

15 октября 2026 года архив на PROD CMP заменён выгрузкой из аккаунта, которому принадлежит текущий API-ключ PROD AI. Ключ DEV AI отличается; секреты и ID генераций в JSON не сохраняются. За тот же исторический интервал получено 2 021 LLM-вызов за 471 активную минуту, 2 626 405 входных и 223 879 выходных токенов. Учтены `openai/gpt-4o-mini` и `openai/gpt-5.6-luna-20260709`; пиковые значения одной минуты — 182 446 TPM и 109,4 TPS. Суммы сверены с агрегатными запросами OpenRouter, отдельно для периода до 6 октября 00:00 UTC и для оставшихся 8 часов 50 минут. Прежний тестовый JSON сохранён на CMP-хосте как резервная копия. Архив содержит вызовы через PROD API-ключ; API не помечает каждый вызов как пользовательский, поэтому отдельные отладочные обращения по тому же ключу возможны.

### Funnel и идентичность прежних чатов

Counters start at deploy. They are Redis keys `metrics:*` on the AI host and do not use the dialogue TTL. Old `session:*` history is not backfilled. Ruble rates are constants in `ai-service/app/metrics.py`: public USD list price times `RUB_PER_USD`, not env.

The same page shows the Telegram funnel for the last 14 Moscow days. Start is the first `/start`. Activated is the first voice, even if the reply fails. Engaged is the third finished clip, including the clarify line. Returned counts a later calendar day when the person sends `/start` or a voice again; the same person can count on more than one day. A `?start=` payload of 1–64 letters, digits, `_`, or `-` is stored once and is not replaced. An empty `/start` stays `direct` until a real payload arrives.

The chat table shows `@username · first last` from the private Telegram chat, or `—` when neither exists. Ktor sends `username` (without `@`) and `name` with every `/internal/funnel/start` and `/internal/funnel/voice`; ai-service stores the latest values in `metrics:chat:{sessionId}` next to `turns`. A chat appears only after its first finished turn, and its name fills in on the next `/start` or voice after deploy.


## Onboarding analytics

> Исторические voice-onboarding когорты v1/v2/v3 и сохранённые отчёты. Новые попытки этого пути в SP-01 не создаются. Конверсию текстового знакомства считает [tutor analytics](tutor-chat.md#sp-01-постоянный-текстовый-чат-с-сашей), отдельно от этих знаменателей.

The 30-second onboarding flow writes new attempts as `v3`. Its decision counters include `30s_checkpoint_reached`, `results_at_checkpoint`, `continued_to_2min`, `results_early_after_continue`, `results_at_2min`, `feedback_opened`, and `practice_menu_opened`. The final result uses the full accepted recording duration. The legacy required-stage journey below describes `v1`/`v2`; its 60/90/120-second and goal/reminder steps are optional or absent in `v3`. Compare versions separately and use the new decision counters for `v3` branch conversion. See [совместимость удалённого пути](tutor-chat.md#совместимость-сохранённых-систем).

Status: local implementation of the контракт ниже. Live DEV acceptance and before/after latency measurements remain release gates.

### Storage and identity

`/admin/metrics/onboarding` uses the metrics password and reads Postgres (`DATABASE_URL`). Without a database, the bot still works and the page reports that history is unavailable. Redis holds only the current onboarding attempt; Postgres keeps historical attempts and events. The analytics version is explicitly `v2` for new attempts. Existing `v1` rows remain `v1`; missing historical events are not reconstructed. A `run_id` identifies one attempt, `session_id` connects attempts for a Telegram user, and the first `/start` attempt is primary. Later `/start`, `/onboarding`, and automatic restarts have distinct triggers. `/start` deep-link source is stored when supplied; `direct` in the filter represents a missing source.

The schema stores timestamps, categorical statuses, numeric durations and counts. It does not store audio, transcripts, correction text, profile text or button-message content. The onboarding dashboard adds a 90-day cleanup for username/chat ID snapshots; aggregate history remains. The product must choose acceptable data loss before adding a durable queue.

### Event contract

| Fact | Occurs when | Idempotency |
| --- | --- | --- |
| Attempt start | Ktor has accepted a new onboarding run | `run_id` |
| `lets_chat_at`, page/card/goal/reminder/profile/bye marks | The corresponding Telegram reply or edit succeeded | First timestamp per `run_id` and mark |
| Onboarding voice | Processing or delivery finished, including classified failure | `(run_id, Telegram request_id)` |
| `result_delivered_at` | The closing result reply was delivered, including a successful cached retry | First timestamp per `run_id` |
| `retry_requested`, `retry_recovered`, `result_build_failed`, `text_hint`, `pre_begin_voice_hint` | Handler reached the named action | `(run_id, event_key)` |
| First practice, D1, D7 | A later ordinary voice was recognized after primary onboarding completed | First timestamp per primary attempt and milestone |

`received_at` is when the bot received a voice; `created_at` is after processing and Telegram reply. A voice's `processing_ms` includes clip processing and reply delivery. Its `speech_before_sec` and `speech_after_sec` are cumulative recognized speech, even when a recording contributes no speech. `telegram_duration_sec`, `recognized_duration_sec`, and `voice_index` are separate facts. The 30/60/90/120-second marks are first crossings, not four extra events per recording. The reply-gap metric measures time from a delivered voice reply to the next received voice, so it represents user wait and is separate from processing time. A button mark means that action completed; Telegram supplies no reliable read/open impression.

Voice outcomes are `recognized`, `no_speech`, `stt_failure`, `processing_failure`, `delivery_failure`, `queue_full`, and `unknown`. STT failure is classified at the STT stage by ai-service. A failed Telegram delivery is distinct from a processed voice. `unknown` covers old or unclassified failures. Text hints and voices before the start button have separate event counts and do not consume the speech budget. Result facts capture whether it was built, whether a CEFR/score exists, the number of displayed Grammar/Vocabulary examples, and whether numeric Fluency measurements exist. Retry is counted separately from a successfully restored result.

### Report definitions

The date filter selects attempts by their Moscow start date (7, 30, or 90 days); version, source, and trigger are further filters. Every selected attempt is a cohort member. A cohort day closes at the beginning of the second following Moscow day. A funnel step counts only if its first timestamp is between attempt start and 24 hours later. “From start” uses cohort size; “from previous step” counts users with both adjacent marks and divides by users with the previous mark. The report flags marks reached without the previous step. Open cohorts are shown separately because their conversion is incomplete. Old `v1` records may lack new fields.

The admin page leads with four numeric summaries, a bar chart of the closed primary funnel, and a daily cohort table. The complete step table, level/goal breakdown, and return denominators are available by expanding a day. Open and repeat cohorts are collapsed by default. Voice outcomes use a second bar chart; errors, timing, result composition, choices, reminders, and data-quality counters use tables. The charts are server-rendered HTML/CSS from the existing report, with no additional database queries or client-side chart library. Percentages still show their counts and denominators.

#### Agent-readable export

The page has a **Скачать JSON для анализа агентом** link. `GET /admin/metrics/onboarding/agent.json` accepts the same `days`, `version`, `source`, and `trigger` filters and requires the same metrics session cookie. It is hidden when metrics are disabled, returns `401` without a session, and returns JSON `503` if analytics is unavailable. The response is an attachment with `Cache-Control: no-store`. It invokes the existing aggregate report once and does not run an AI model or add queries to normal bot replies.

The versioned `onboarding-analytics.v1` export contains generated time, filter values, metric definitions, closed/open cohorts, stable English step IDs, exact conversion numerators and denominators, D1/D7 maturity, voice outcomes and timing percentiles, result and reminder decisions, and process-local write counters. A percentage is `null` when its denominator is zero; D1/D7 fields are `null` when the cohort is not eligible. The export contains no run/session IDs, audio, transcripts, profile text, or individual events. The `analysis_guidance` field reminds an agent to compare mature cohorts, separate technical failures from user drop-off, and avoid causal claims from these observations.

For manual analysis, download the JSON through the authenticated page and give the file to the agent with a request such as: “Find the largest well-supported drop-offs and technical failures. Cite cohort dates, numerators, and denominators. Separate observations from hypotheses, propose what to verify next, and do not infer causality from small samples.” Treat any future external AI upload as a separate sharing decision.

The outcome table includes all selected onboarding voices; stage uses cumulative speech before that voice. Recognition error rate covers `no_speech` and `stt_failure` among selected voices from the last seven days, excluding `queue_full`; processing and delivery failures remain separate. Result-build error rate covers selected attempts that reached 120 seconds in the last seven days. The error blocks show event count, denominator, and distinct affected users. Percentiles use Postgres discrete percentiles. The voice-count distribution counts recognized voices on attempts with a built result.

Result/card/reminder counts cover selected attempts, including events later than the funnel's 24-hour window. The first ordinary practice voice belongs to the first completed primary attempt. D1 is Moscow calendar day after start and is shown only for a closed cohort; D7 is the seventh calendar day and is shown after that day has ended. Return counts use all starters as denominator and also show completed attempts by the end of the respective day. A new onboarding attempt alone is never practice return. These are observational associations; they do not establish why a user stopped or returned.

### Performance and data quality

Analytics writes are buffered during the per-chat action, then executed in order after Telegram delivery and after releasing the chat action lock and voice admission slot. An analytics write cannot delay that delivered reply or the next chat action. This is direct Postgres writing, not a process-memory background queue: an unflushed write can be lost on process crash. Individual write failures are logged and do not alter a delivered reply. Postgres connection wait is limited to one second, socket read to two seconds, and aggregate queries to five seconds. The analytics pool has two connections; the report aggregates in SQL over at most 90 days instead of loading every voice into Ktor memory. The page shows attempted writes, failed writes, and detected missing-attempt writes since this Ktor process started; they reset on restart and are not durable completeness metrics.

The local Flyway/Postgres integration test covers new-schema writing, deduplication, aggregate reading, and migration of synthetic `v1` rows. Deployment still needs a DEV walkthrough on new and existing users, migration verification on real historical data, query plans at expected volume, and matched before/after p50/p95/p99 bot latency and report-time measurements. A small cohort's percentages should not be treated as proof of improvement.

### Activation extension (2026-10-01)

The контракт ниже adds Flyway `V4` without rewriting historical attempts. `onboarding_entries` stores the observed `/start` update key per session, receipt time, eligibility, exclusion category, trigger, source, linked run and invitation delivery. For a new-user cohort, the earliest eligible `start` entry per session is selected **before** the date filter. Existing users and explicit `/onboarding` are outside its denominator. Entry and invitation are written in one idempotent row after the chat action, including when delivery fails; a process crash before flush can lose it. This denominator is therefore observed eligible entries, not guaranteed total Telegram traffic. `begin_pressed_at` is recorded after a valid action is accepted; `first_question_delivered_at` is recorded after Telegram delivery. The existing `lets_chat_at` retains its old meaning.

`onboarding_practice_days` stores one first successful ordinary voice reply per primary run and Moscow date. It requires a delivered onboarding result before the ordinary reply. The existing D1/D7 and first-practice marks are updated in the same transaction. No audio or text is added. A7 is the number of observed eligible users with a delivered result and at least one successful ordinary voice reply within 7 × 24 hours of entry, divided by eligible entries whose full seven-day window has elapsed. Two-day practice counts two distinct Moscow dates inside that same window. An open window is excluded from these two numerators and their denominator. Result and first-practice p50 values use only users reaching the event; they are not conversion rates. Historical attempts with no V4 entry cannot be backfilled into the new denominator.

The dashboard and `onboarding-analytics.v2` agent JSON now include daily entry-to-activation counts, denominators, first-result/practice p50, missing entry-to-attempt links, and per-voice-index outcomes and continuation. Existing JSON fields are retained. A technical outcome or missing next voice is observational; it does not prove the user stopped because of that outcome. Report queries still run in the limited analytics pool and SQL timeout. Production readiness continues to require DEV scenario reconciliation, volume-based query plans, matched p50/p95/p99 response and report measurements, and an explicit retention/event-loss policy.

Local verification: the server test suite and detekt passed with PostgreSQL integration tests enabled. An `EXPLAIN (ANALYZE, BUFFERS)` of the activation query in a disposable local database with 10,000 synthetic entries, 30,000 onboarding voices and 20,000 practice-day rows took about 215 ms with warm buffers. This is one query on one synthetic distribution, not a production report-time or bot-latency measurement; the inserted rows were rolled back.

### Current reminder settings (2026-10-01)

The historical `reminder_set_at` counter still means that an onboarding user saved a time at least once; changing or clearing a reminder does not erase that event. The dashboard now also requests an authenticated, aggregate-only `GET /internal/reminders/summary` from ai-service. It reads the current `reminder-time:{sessionId}` settings in Redis, counts valid Telegram session IDs with a valid saved Moscow `HH:MM`, and groups them into 24 hourly buckets. A changed time moves the user to the new bucket; clearing the setting removes them. A saved setting counts as active opt-in, even if Telegram later blocks delivery. It is not a delivered-reminder count.

This is a live snapshot **across all users**, independent of the dashboard's onboarding date/version/source/trigger filters; the page and `onboarding-analytics.v3` JSON label that scope. No user IDs or exact individual times enter the export. AI-service scans keys in batches when an authenticated admin opens the page or downloads JSON. Normal bot replies and reminder scheduling make no extra analytics writes or model calls. If ai-service is unavailable, the current-settings block is marked unavailable rather than shown as zero; the Postgres onboarding report remains available.

### Daily LLM request counts (2026-10-01)

`GET /internal/metrics` and `/admin/metrics` now report `llmRequests`, `llmFailures`, and `llmRequestsByPurpose` for the current Moscow day. The four purposes are `onboarding` (all `complete_json` calls), `reply`, `notes`, and `session_review`. Each application attempt to call `chat.completions.create` counts once, including a failed attempt and the application's retry after a retryable error. The successful response's usage feeds token totals and TPM/TPS; an error has no known token usage and does not add tokens. Calls that return malformed or empty JSON still count as successful provider attempts. These counters are aggregated in the existing Redis daily hash with the existing metrics write, without another database or model request in the reply path. If Redis metrics are unavailable, recording fails open and the bot response continues; the dashboard may undercount. SDK-internal HTTP retries and provider-side accounting are outside this application-attempt count.

The onboarding page also shows this daily summary, and `onboarding-analytics.v4` agent JSON includes `llm_requests_today` with the same counts, purpose breakdown, day, and scope. Both load it only when an admin opens the page or downloads JSON; if ai-service is unavailable, the block is unavailable/null while the Postgres report still works. The daily `onboarding` count is across all users and onboarding versions, not a count for the filtered onboarding cohort. Historical onboarding analytics and the agent export do not use it as a cohort denominator.

### Selectable LLM period (2026-10-01)

Both admin pages now have inclusive `llmFrom`/`llmTo` Moscow date inputs, defaulting to today. The selected period is independent of onboarding cohort filters and is retained when those filters change or the agent JSON is downloaded. The LLM section of `/admin/metrics` uses the selected period; token, STT, TTS, DAU, cost, and funnel sections still describe today. `onboarding-analytics.v5` exports `filters.llm_from`/`llm_to` and `llm_requests_period` with the exact dates, total requests, failures, and purpose counts. The old `llm_requests_today` field remains populated only when today's single day is selected.

Authenticated `GET /internal/metrics/llm?from=YYYY-MM-DD&to=YYYY-MM-DD` reads the already stored daily LLM counters and returns their sum. Both API and admin UI reject reversed dates, future end dates, and periods longer than 366 inclusive days. Redis reads the bounded set of daily hashes in one pipeline without taking the process lock used by LLM writes; normal LLM calls keep their existing write count. The report does not backfill calls made before LLM request counting was deployed, so an older range with zero counters is not evidence of zero real requests. If this endpoint is unavailable, the LLM section shows unavailable without hiding the other onboarding analytics.

### Journey, stage errors, and recent users (2026-10-05)

The top of `/admin/metrics/onboarding` now answers four questions on the same 7/30/90-day Moscow start cohort: number reaching each required stage, closed 24-hour attempts stopping after each stage, errors and unsuccessful outcomes by user stage plus technical stage, and the 15 most recent distinct users. The default cohort selects each session's first observed eligible `/start` **before** date filtering, including an entry whose attempt link is missing. The optional repeats mode counts non-primary attempts; its recent list shows the newest matching repeat per session. Version/source/trigger filters apply to all four blocks. The older activation, D1/D7, timing and LLM sections remain available in an expandable detail area and retain their original definitions.

The required path is invitation delivered, begin pressed, first question delivered, first voice received, first recognized voice, 30/60/90/120 seconds of recognized speech, result delivered, result opened, minutes choice shown and made, reminder offered, and reminder resolved. Zero minutes is a valid choice. Reminder acceptance only resolves after a time is saved; `not_now` resolves immediately. Grammar, Vocabulary, Fluency, Profile, and Bye are optional actions, not required sequential drop-off stages. A stage must be observed inside 24 hours of entry. A stopped count includes only attempts whose 24-hour window has closed, whose preceding required facts are complete, and that did not reach the next stage. Open and missing/out-of-order records are reported separately. A recorded error beside a stopped attempt is an association, not a proven reason for leaving.

Flyway V7 stores nullable Telegram chat ID and a bounded username snapshot at entry/attempt start, a classified invitation failure, and bounded failure-stage/code values on new voice/event records. The voice error's user stage comes from cumulative speech before that voice; its technical stage comes from the classified failure. `no_speech` is an unsuccessful outcome, not a technical STT failure. The protected HTML page shows username or chat ID, last stage, state, and last error for at most 15 users. `onboarding-analytics.v6` agent JSON contains only the new aggregates, never usernames, chat IDs, session IDs, run IDs, or individual events. The HTML response uses `Cache-Control: no-store`. The username and chat ID snapshots are cleared in small background batches after 90 days, without removing aggregate rows.

The Postgres journey uses bounded SQL aggregates over the selected period and a separate `LIMIT 15` individual query. Analytics still writes after delivery outside the chat queue and fails open; missing events may cause incomplete or underestimated reports. New fields are not backfilled into old attempts. Local compiler and test results do not establish DEV/PROD event completeness, Telegram delivery, latency, or query plans at production volume; those remain release checks in the контракт ниже.

### Recovery after errors and first independent practice (2026-10-05)

The error table now shows three outcomes after the **first error of each stage/technical-stage/reason type in one selected attempt**: an observed repeat action, reaching the next required journey stage, and finishing all required stages. Recovery denominators include only errors observed in attempts whose full 24-hour start window has closed. Each outcome must occur after that error and before the window ends. Multiple matching failures remain separate in the event count but contribute one affected attempt to each recovery numerator. The primary mode counts users; repeat mode counts attempts. These are associations after an error, not estimates of how many users the error caused to leave.

For voice errors, a later received voice is a repeat; for an invitation error, another eligible start from the same session is a repeat, even if it creates another attempt. For callback errors, a later callback in the same technical stage is a repeat. A result-build error can also be followed by a result retry. Callback attempts are now recorded once per Telegram callback ID as `action_attempt`, buffered outside the chat action lock like other analytics writes. Older callback failures without an observed initial `action_attempt` are excluded from the **repeat-action denominator**, shown as `—` if that denominator is zero. The next-stage and completion numerators stay tied to the selected attempt; a later invitation on another attempt does not silently complete the original attempt. New callback events add one small Postgres write per handled callback; no new AI call or Telegram message is made.

The practice block is shown for primary users only. Completion means every required journey stage, through resolving the reminder, was observed in the first 24 hours. The metric then waits for a recognized **ordinary** voice with a delivered bot reply after that completion. Its 24-hour and 7-day windows start at the final stage, and each denominator includes only completions whose own window has fully elapsed. Flyway V8 adds `post_completion_practice_observable` and `first_post_completion_practice_at`; the latter is updated inside the existing successful-return transaction. A voice before the final step may still count toward the older A7 metric, but does not count here. Historical completions are not marked observable or treated as zero-practice users. The agent export is `onboarding-analytics.v7` and contains only aggregate recovery and practice counts, never individual user rows.

The admin page has a direct button to the provisioned `speaky-v2` Grafana dashboard on the same host's HTTPS port `8443`. Grafana keeps its own login.

## Дашборд практических звонков Telegram

Локальная реализация контракт ниже. Маршруты `/admin/metrics/calls` и `/admin/metrics/calls/{callId}` используют существующую авторизацию `/admin/metrics`, `Cache-Control: no-store` и московский часовой пояс. Сводка выбирает сегодня, 7 или 30 дней по дате начала звонка. История начинается с включения сбора: старые звонки из Redis не восстанавливаются.

### Сбор и смысл полей

Локальное расширение 2026-10-24: после даты перехода дневного счётчика `recognizedSeconds`
содержит сумму интервалов речи VAD, а не длительность файла. Старая история остаётся
по длительности распознанной записи. `recordingSeconds`, `durationMethod`,
`durationSource` и `durationFallbackReason` позволяют различить эти случаи.
Событие `voice_processed` сохраняет источник в `state`; длительность Telegram файла
по-прежнему хранится в `voice_received`. Flyway V24 сохраняет метод в компактных фактах
и сводках после удаления исходных событий. DEV Telegram ещё не проверен.

Flyway V10 хранит идемпотентные `telegram_call_events` в Postgres; V9 остаётся миграцией `interaction_audit`, уже применённой на DEV. Без `DATABASE_URL` используется ограниченное хранилище в памяти. События содержат `callId`, Telegram chat/message ID, время, тип, небольшие числовые поля и ограниченный статус. Аудио, транскрипты, текст ответов и исправлений не сохраняются. `(chat ID, message ID)` голосового сообщения соответствует существующему детерминированному `attempt_id` на странице ошибок. Исправления, ответы и Subtitles привязаны к ходу или Telegram message ID ответа.

В AI-service `CallStore` остаётся источником открытого звонка, распознанной речи и дневной цели. Внутренние ответы `calls/open`, `calls/start`, `calls/status`, `calls/end` дополнены временем открытия/закрытия, `callId` и причиной закрытия. В ответе клипа `recognizedSeconds` относится только к текущему распознанному ходу, а не к разнице дневных сумм. `calls/status` не создаёт звонок; после неудачного starter Ktor использует его для связи попытки кнопки с уже созданным звонком.

Ktor фиксирует получение `Start call`, открытие и доставку вступительного аудио, голосовые и их Telegram duration, результат обработки, принятое модельное исправление и результат отправки карточки, аудиоответ или текстовую замену, время ожидания от приёма голоса до ответа, нажатия Subtitles и результат показа, `End call`, причину закрытия и доставку review. У `End call` теперь есть `callId` в новых callback-кнопках; старые `call:end` продолжают работать. Повторное нажатие или повторный Telegram update не увеличивает счётчик того же события.

«Конец диалога» — доставка ответа на последнее голосовое либо время последнего голоса с пометкой «без ответа». «Конец звонка» — отдельное фактическое закрытие. На следующий московский день прежний звонок закрывается при следующем обращении, поэтому время закрытия может быть позже полуночи. Длительность файла Telegram и распознанная речь показываются независимо. Вступительное аудио не входит в число ответов на пользовательские голосовые. Текстовая замена не считается аудиоответом. Доставка означает, что Telegram принял отправку, а не что человек прочитал/прослушал.

### Эксплуатационные границы

`CallEventRecorder` отправляет записи через ограниченную очередь вне основного ответа и считает ошибки/переполнение; потери видны на странице. Детальные события удаляются после 30 дней небольшими пакетами. Чтение сводки ограничено последними 50 000 событий; достижение лимита помечает агрегаты как неполные. Страница детали ограничена 1000 событий одного звонка. Страница Subtitles связывает старые callback с `callId` через сохранённый Telegram bot message ID, но сам текст Subtitles по-прежнему находится в памяти Ktor и после перезапуска может быть недоступен; такое нажатие учитывается как `unavailable`.

Исходная локальная проверка 2026-10-05: `:server:detekt`, `:server:test`, 275 тестов ai-service и Flyway/JDBC на временном Postgres. На 60 000 синтетических событий `EXPLAIN (ANALYZE, BUFFERS)` для ограниченного чтения сводки дал около 66 мс на локальной машине. Позднее базовый дашборд был развёрнут в PROD; полная проверка реальных Telegram callback/delivery и задержек под нагрузкой не проводилась.

### Режимы и выбор ситуации (локальное расширение 2026-10-09)

План и определения — в контракт ниже. Flyway V13 добавляет к существующему событию ограниченный `scenario_kind`: `free`, `job`, `manager`, `custom`. Для прежних звонков значение отсутствует и отчёт показывает «Неизвестно». Переход голоса в уже открытый сценарный звонок не меняет его режим на `free`.

Ktor пишет идемпотентные факты принятого Telegram меню, выбора каждого варианта, Back и результата ввода описания Custom. Само описание, аудио и транскрипт в аналитику не попадают. `/admin/metrics/calls` показывает людей, звонки, повторное использование, начатые диалоги, распознанную речь, ответы, сбои и review по режиму; отдельно — этапы выбора ситуации и разбивку по дням. Речь считается по распознанным секундам, а не по времени, когда звонок оставался открытым. Сравнение режимов использует фильтры периода/источника/статуса/ошибки; события меню используют только период. Количество выборов и открытых звонков следует читать с учётом границы периода и потерь событий, показанных на странице.

Это расширение пока локальное: после выкладки накопление новых фактов начнётся с момента обновления Ktor. Из Redis старые выборы и режимы не восстанавливаются.

## Регулярность Speaky

Реализовано локально по контракт ниже. Выкат и приёмка PROD выполняются отдельно.

### Определения и интерфейс

`/admin/metrics` показывает блок «Регулярность»: DAU, WAU, DAU/WAU, дневные графики, группы 1/2/3/4–7 активных дней, недельные когорты D1/D3/D7/D30 и среднюю распознанную речь на разговор. По умолчанию весь отчёт выбирает последние семь **завершённых** московских суток. Явный выбор сегодняшнего дня показывает предварительные значения.

Активность — запись завершённого голосового хода AI в `metrics:dau:*`. Уточнение при нераспознанной речи входит; факт предшествует доставке Telegram. Один нормализованный Telegram chat ID — один пользователь. Старые форматы ID одного чата объединяются; приложения CMP исключаются. Текущий фильтр нагрузочных тестов остаётся в месте записи события.

WAU на дату E — объединение ID за E−6…E, DAU/WAU — отношение значений на E. Группы активных дней используют это же окно, не длину стрика. Первый день сохранённой истории потенциально неполный, поэтому окна, содержащие его, скрыты. После начала истории отсутствующий дневной набор считается пустым: историческую потерю сбора нельзя надёжно отличить от отсутствия пользователей. Это ограничение указано в интерфейсе.

Начало когорты — первый наблюдаемый завершённый ход AI. Строки — восемь недель понедельник–воскресенье относительно даты E. Первый доступный день и неделя помечаются как неполная история. Возврат — активность ровно через N календарных дней. В знаменателе только люди, чей целевой день завершился; показываются процент и returned/eligible, частичная зрелость отмечается. Наблюдение возвратов продолжается до вчерашнего дня, даже если диапазон входа в когорту выбран в прошлом. Дата наблюдения подписана отдельно.

`/admin/metrics/streaks` использует ту же новую таблицу. Старый retention от первого отправленного голосового сохранён под явной исторической подписью; его прежние правила не подменены новым определением. Продуктовое поведение стриков не меняется.

### Сбор и API

`ai-service/app/habit_analytics.py` — общий расчёт для overview, streaks и Prometheus. Фоновое обновление раз в 60 секунд читает Redis пакетами до 200 дневных наборов; голосовой pipeline не ждёт этот расчёт. Запросы отчёта могут использовать тот же кеш, при необходимости обновляя его. Скрейп читает только готовый ограниченный снимок. Ошибка обновления даёт `healthy=false`, а не нулевую аудиторию. UI считает снимок устаревшим через 180 секунд.

`GET /internal/metrics/overview` добавляет необязательное поле `habits`; `GET /internal/metrics` — `streaks.habits`. DTO имеют совместимые defaults. Поля включают `version=completed_ai_turn_v1`, timezone, asOf, observedThrough, historySince, healthy, updatedAt, days, cohorts и periodRetention. Старые поля остаются.

Новые Prometheus-метрики имеют префикс `speaking_habit_`: dau/wau/ratio с day, current_dau/current_wau/current_ratio, active_days с bucket, cohort_size/cohort_partial_history с week, retention_eligible/retention_returned с week/offset; также source_healthy, updated_seconds и as_of_seconds. Экспорт ограничен 30 днями и 8 когортами, без пользовательских labels. Исторические временные ряды Prometheus сохраняются по его существующей политике.

### Длительность

Локальное изменение 2026-10-24: новые практические дни после активации считают
речевые интервалы VAD. Старые дни остаются на длительности записей с паузами.
После V24 view `habit_call_daily` дополнительно показывает `legacy_calls`, `vad_calls`,
`mixed_calls` и `fallback_turns`; панель подписывает их рядом со средним. При ошибке
или перегрузке VAD отдельный ход использует длительность записи и входит в
`fallback_turns`. DEV проверка точности и задержки ещё открыта.

Flyway V15 создаёт `habit_call_summaries`, компактные `habit_call_facts` для дедупликации и агрегатное представление `habit_call_daily`. В этих таблицах нет chat ID, username, текста или аудио; остаются call ID, локальный ключ факта, времена и секунды. Триггер после INSERT/UPDATE `telegram_call_events` обновляет факты и сводку в той же транзакции. Конкурирующие события одного звонка сериализуются блокировкой строки сводки. Повторная обработка того же message ID не прибавляет секунды.

Миграция заполняет имеющуюся историю пакетами по 500 исходных событий внутри миграционной транзакции. Старые уже удалённые данные не восстанавливаются. Компактные факты и итоги удаляются через 365 дней после последнего события звонка, пакетами по 1000 звонков; исходные события продолжают жить 30 дней. Перед production-миграцией нужно учитывать продолжительность блокировки на фактическом объёме базы.

Среднее = сумма известных распознанных секунд / число подходящих звонков / 60. Подходят закрытые практические звонки с положительной распознанной речью, известным началом, совпадающими входящими и обработанными ходами и известной длительностью каждого обработанного хода. Открытые, пустые и обнаруженно неполные звонки показываются как исключённые. Сводки без начала попадают в исключения по дате последнего события. Выбор периода идёт по московской дате начала; события после полуночи остаются в том же звонке. Время позднего закрытия не увеличивает секунды речи.

Самописный отчёт и Grafana читают одно SQL view. Потери существующей очереди событий могут влиять на полноту; UI показывает счётчик ошибок текущего процесса, неизвестные потери за прошлые процессы не восстановимы. В локальном режиме без PostgreSQL компактные факты сохраняются только в памяти процесса, что явно отмечено.

### Grafana и применение

В `speaky.json` добавлен раздел «Регулярность Speaky». Недельное окно по умолчанию заканчивается вчера; дата среза и обновление показаны отдельными карточками. Панели активности и когорт читают актуальные instant-снимки; глобальный временной фильтр не пересчитывает их определения. Полная настройка дат доступна в самописном отчёте. Длительность использует московские календарные даты глобального диапазона Grafana.

Таблицы объединяют запросы по дате/неделе с `outerTabular` ([Grafana Join by field](https://grafana.com/docs/learning-paths/data-transformation/join-by-field/)). Retention не использует исторический максимум процента. При ошибке источника или возрасте снимка более 180 секунд панели скрывают показатели.

`infra/monitoring/deploy-remote.sh` ожидает view и выдаёт существующей роли `speaking_cost_ro` только дополнительный SELECT на `habit_call_daily`; прямого доступа к компактным фактам или исходным событиям не добавляется. Перед применением обновлённого provisioning должен быть запущен Ktor с V15. AI-service можно обновить первым: новые DTO необязательны. Откат к старому коду возможен без удаления V15; не удалять миграцию из уже применённой истории.

### Локальная проверка

Проверяются нормализация ID, граница Москвы, нагрузочный фильтр, разные окна, D1/D3/D7, зрелость, отсутствие данных и недоступность Redis. JDBC-тест использует временный PostgreSQL: повтор события/сообщения, переход полуночи, позднее закрытие, сохранение итогов после pruning, истечение 365 дней. Отдельно проверено заполнение существующих событий и read-only доступ только к view.

На локальных 60 000 синтетических сводок EXPLAIN ANALYZE запроса за 18 дней использовал `habit_call_day_idx`, около 1,3 мс. Это проверка SQL, не оценка production-пропускной способности или накладных расходов под рабочей нагрузкой.


В финальном локальном прогоне: 372 Python-теста и 213 Kotlin-тестов с `TEST_POSTGRES_URL` на отдельной временной базе, `:server:detekt` и проверки HTML пройдены. Превью открыто в headless Chrome на широком и узком экране; ширина документа не превышала фактический viewport. Все 31 новых PromQL-выражения прошли парсер; SQL панели Grafana выполнен на временной базе. Новый API-тест сверяет дни и когорты overview/streaks и наличие метрик экспорта.

Сам Grafana с новыми панелями и выполнение PromQL в Prometheus локально не запускались: загрузка временного бинарника прерывалась сетью. Поэтому визуальная приёмка Grafana и проверка реальных скрейпов остаются этапом после разрешённого выката; синтаксическая проверка не заменяет её.

## Аналитика напоминаний в админке

> Исторические onboarding sends остаются в отчётах; новые onboarding followups выключены. Practice/remind работают отдельно. [Граница совместимости](tutor-chat.md#совместимость-сохранённых-систем).

`/admin/metrics/reminders` использует существующий пароль/cookie админки и `Cache-Control: no-store`. Локальная портовая страница `/admin/monitoring/reminders` показывает те же блоки за ограниченным мониторинговым портом. Переключатель 7/30 дней относится к историческим блокам. Текущий срез сохранённого времени не зависит от периода.

- Распределение `00:00–00:59` … `23:00–23:59` читает `GET /internal/reminders/summary`. Знаменатель — все Telegram-пользователи с валидным сохранённым временем по Москве. Показываются все 24 часа, включая нули, число пользователей и процент. Смена времени переносит пользователя между часами; отключение удаляет.
- Конверсия предложения — уникальные Telegram session ID с `reminder_set_at >= reminder_offered_at` в той же попытке / уникальные Telegram session ID с `reminder_offered_at` в выбранном периоде. Источник — Postgres-таблица `onboarding_attempts`. Это историческое действие онбординга; текущие настройки, `/remind` и поздние отключения в знаменатель не входят.
- Эффективность по выбранному часу берёт только автоматические успешные отправки. Час фиксируется при claim и передаётся в отчёте отправки; первый голос за 24 часа атрибутируется существующим правилом последней отправки. Показываются семь или тридцать полных московских дней отправки, заканчивающихся позавчера. Ручные и тестовые отправки исключены. Это наблюдаемая доля ответов, не причинный эффект времени.
- Исходы авторассылки за день: `skipped_active` — уникальные настроенные пользователи, уже практиковавшиеся при наступлении временного окна; `autoSent`, `autoBlocked` (Telegram 403), `autoFailed`; неизвестный исход = `claimed_auto - autoSent - autoBlocked - autoFailed`, не меньше нуля. Claim и счётчик claim записываются вместе. При простое планировщика отсутствие наблюдения не считается пропуском. Повтор отчёта с тем же `reportId` не увеличивает счётчики.
- Изменения настройки: уникальные Telegram-пользователи за 7/30 дней, у которых появилась настройка из пустого состояния (`set_from_empty`, включая повторное включение), сменилось время (`changed`) или удалена активная настройка (`cleared`). Один пользователь может попасть в разные действия. Повторная запись того же времени и повторное отключение ничего не добавляют. В Redis сравнение и запись защищены `WATCH`/`MULTI`; memory-store использует lock.

Новые дневные Redis-агрегаты (`reminder:analytics:day:*`), множества дедупликации (`reminder:activity:*`), счётчики claim и `reportId` хранятся 40 дней. `reminder:analytics:since` отмечает начало сбора без TTL. Старые отправки и изменения настроек не восстанавливаются из текущих ключей. Существующие дневные счётчики, таблицы рассылок и пользовательские сценарии не изменены. Страница напоминаний получает новые данные и текущий срез часов вместе с `/internal/metrics`: прогноз и часы используют один обход настроек Redis. Отдельный `GET /internal/reminders/summary` сохраняется для страницы онбординга и совместимости при обновлении сервисов. При недоступности Postgres или ai-service зависимый блок показывает «недоступно».

Локально пройдены полный набор Python-тестов, `:server:test` и новый интеграционный Postgres-тест на временной локальной базе. Не проверены DEV/PROD, реальная доставка Telegram, план SQL на рабочем объёме и задержка Redis SCAN под нагрузкой. До выкладки сверить одну цепочку сохранить → изменить → отключить, авто пропуск после практики и отправка → голосовой ответ в DEV.

### Incomplete onboarding reminders

A separate block on both reminder pages shows incomplete-onboarding send outcomes, mature 24-hour return/voice/result rates, unique 7-day result/practice cohorts, and breakdowns by day, stage, attempt number, inactivity and template version. Definitions and storage boundaries: [технический контракт](practice.md#существующие-reminders-и-streak). Aggregate-only JSON is available at `reminders/onboarding.json?days=7|30` with the same access control. These metrics do not enter daily practice reminder totals.

## Reminder effectiveness dashboard

Status: implemented locally on `feature/onboarding-nudge-analytics`; PostgreSQL integration tests passed. Live Telegram acceptance and deployment remain outstanding.

`/admin/metrics/reminders?days=7|30` starts with a short decision view. Existing detailed tables and send controls are retained inside a collapsed diagnostics section. `/admin/metrics/soft-nudges` has the same five-minute soft-nudge definition. Only Telegram-accepted sends enter conversion denominators. Telegram does not report whether a message was read. All dates are Moscow calendar dates; current saved and inferred time distributions are snapshots, not send-period cohorts.

The main view has a separate onboarding table with one row per stage that sent a reminder. It shows sends, pending windows, later-stage progression, button clicks, and first voice. A total row uses the same send-level definition. An expandable table breaks each stage down by recorded `copy_id` and shows its current catalog text and mature-window conversion. Missing current text is identified as such. Small samples and different users between variants limit conclusions.

Time distribution uses 24 one-hour rows per population, with user count, meter bar, and one-decimal share. Saved times are a current active-setting snapshot without a date filter. Inferred times use recipients of accepted practice sends in the selected period. The former four-hour combined table was removed.

The primary outcomes are:

| Flow | Success | Window |
| --- | --- | --- |
| Soft onboarding interruption | First incoming voice | 5 minutes after send |
| Soft open-call interruption | Incoming voice or End Call on that call, counted once | 5 minutes after send |
| Daily unfinished onboarding | Reached a later observed funnel stage | 24 hours or next accepted daily reminder, whichever comes first |
| Daily practice, user chose time | First incoming voice | 24 hours or next accepted daily reminder |
| Daily practice, bot inferred time | First incoming voice | 24 hours or next accepted daily reminder |

The denominator contains only sends whose full attribution window has closed. Pending sends are shown separately. Button clicks and first voice are intermediate rows for onboarding; button clicks are intermediate rows for practice. A person can enter more than one send-level denominator across days. The dashboard does not claim a causal effect: users who set their own time can already be more motivated, and general/personalized copy alternates by day rather than random assignment.

New `reminder_buttons.source` and `copy_kind` capture the category and actual text kind when the bot accepts a message and registers its button. A successful callback records `clicked_at`; previous buttons remain in storage after deactivation, so historical clicks are preserved. The configured-time practice cohort uses these records. The inferred-time practice and unfinished-onboarding cohorts use `onboarding_nudges` and their stage/copy metadata. The inferred time distribution uses each recipient's latest recorded median time on an accepted practice send in the selected period. Configured time buckets come from the current reminder-setting summary. These distributions have different populations and must not be compared as a controlled experiment. Older sends have no new category or recorded median minute and cannot be backfilled reliably.

Automatic configured-time attempts also write `configured_practice_attempts` after sending. Follow-up replacements do not enter this table. This keeps configured-time blocks and failures separate from inferred-time sends even when one scheduler claims both. Known Telegram errors have a numeric `telegram_*` code; local and transport failures use `transport_or_internal`. The primary conversion still uses Telegram-accepted button records, so a failed database write can appear as an unknown gap between the send runner and this dashboard.

The first voice uses incoming Telegram interaction metadata, retained for 30 days; it does not require successful transcription. Onboarding progression uses durable timestamped funnel milestones from the same attempt. The terminal `reminder_resolved` stage advances when a practice voice arrives. Soft-nudge voice response remains durable in `soft_nudges.returned_at`, and End Call uses the timestamped `end_pressed` event for the interrupted call. If both actions happen, the combined soft-call numerator is one.

The main diagnostic table separates 403 blocks, other failed sends, and uncertain outcomes where the source records them separately. Its cells show counts and percentages of attempts. An expandable day table shows when errors occurred by source; configured-time attempts also have a code breakdown. The detailed Telegram error reason is not consistently stored across soft and inferred reminder flows, so the dashboard does not fabricate one. Existing day and stage breakdowns remain in the collapsed tables. Operational checks should verify that the five-minute soft metric, callback clicks, source split, and first-voice windows match live Telegram behaviour before relying on production percentages.

## Vocabulary Builder analytics

Status: implemented locally on 2026-11-04. Not deployed or checked with real DEV Telegram lessons. See the контракт ниже.

### Access and data path

The protected page is `/admin/metrics/vocabulary`, with a metadata-only lesson detail at `/admin/metrics/vocabulary/{lessonId}`. It uses the existing `METRICS_PASSWORD` session and `Cache-Control: no-store`. The AI service appends content-free facts to Redis Stream `vocabulary:analytics:v1` in the same transaction as a lesson state or review-trace change. Ktor polls `/internal/vocabulary/analytics`, writes a batch and its cursor to Postgres in one transaction, then acknowledges the stream. Telegram acceptance is a separate Ktor fact. Event IDs make replay idempotent. V25 creates the event and cursor tables.

Only a Telegram chat ID, opaque lesson/item IDs, timestamps, event kind, format, counts, phase, classification, message IDs, and timing enter this store. No word, translation, transcript, prompt, response text, or audio enters it. Manual versus review-sourced words are separated; example versus suggestion is retained only as a source class. Detailed technical events expire after 30 days. Compact lesson, word, and outcome facts expire after 365 days. Without `DATABASE_URL`, a process-local store is labelled on the page.

### Counting rules

- A start attempt is a Telegram button event. A created lesson is the AI state commit. An opening is delivered when Telegram accepts the bot message. These are separate counts.
- People are distinct Telegram chats. Lessons are distinct lesson IDs. Practised words are pairs of lesson ID and item ID. The period and format filter apply to the creation date of lessons. Late outcomes remain attached to their creation cohort.
- Full completion means the AI recorded a completed lesson with all card results. Partial completion is separate. A lesson without a terminal event remains active for one hour after its last fact, then enters a stop bucket.
- `used` means the product model accepted the learner's own phrase in the Use phase. A natural answer without the target is not a use success or an error. Card correctness and use success remain separate measures.
- D1/D7/D30 require a second delivered lesson on exactly that Moscow calendar day after the first delivered lesson. Only cohorts whose target day has passed are eligible. The weekly table groups people by the Monday of their first observed lesson. The first observed cohort can contain earlier users.
- Word revisit requires a later lesson with the same item after its first recorded due timestamp. Repeat Use success requires an accepted Use in such a later lesson. Due timestamps are persisted after completed format-5 lessons; older formats have no equivalent due fact.
- A failed request, failed Telegram delivery, and an unanswered lesson are distinct. Retry success resolves the matching message where available. The diagnostic page shows safe identifiers and event metadata only.

### Completeness and interpretation

The page shows source health, last successful sync, a queue-backlog signal, local write failures, and whether storage is volatile. Collection begins when this code is deployed; older lessons are not backfilled. A Redis host crash before persistence can lose uncommitted stream facts. The Telegram event queue can lose its buffered facts on process crash. The dashboard cannot detect either loss after restart, so these counters do not prove complete history. The 365-day report currently reads the bounded event range into memory; measure this query with production-like volume before PROD release. No before/after latency or real Telegram acceptance has been measured locally.

Local checks: Python test suite, Kotlin server test and Detekt, and V25/JDBC tests on a temporary Postgres. DEV acceptance and PROD deployment require separate decisions.

## Стоимость успешного голосового обмена

Статус: новая схема реализована и проверена локально 15 октября 2026 года. Выкат на DEV/PROD и сверка обычного голосового обращения остаются отдельными действиями. Достоверная история начинается только после выката **обеих** частей: CMP и AI.

Целевое действие — входящее голосовое Telegram с непустой распознанной речью и доставленным голосовым ответом. Цена действия — сумма фактических цен всех STT, LLM и TTS вызовов для этого голосового, включая ошибки, повторы и запасные маршруты. Обращения без доставленного голосового ответа не входят в знаменатель. Помеченные нагрузочные тесты не входят в пользовательский учёт.

AI создаёт отдельный `call_id` для каждой попытки, связывает его с `attempt_id` и кладёт в Redis. CMP забирает записи по защищённому внутреннему API и подтверждает их только после сохранения в PostgreSQL. Повторная передача одного `call_id` не добавляет стоимость второй раз. Redis настроен на AOF; в PostgreSQL отдельные таблицы `voice_cost_calls` и `voice_cost_exchanges` хранят историю минимум 185 дней. Тексты, аудио и ключи в этих таблицах не сохраняются.

Цена LLM и STT читается из `usage.cost`, **если** её вернул провайдер. Для OpenRouter TTS AI сохраняет `X-Generation-Id`, сразу отдаёт аудио, а затем в фоне запрашивает `/generation` через 15 секунд, 1, 2, 10 и 60 минут. HTTP 404 и временные ошибки приводят к следующей попытке. Ожидание и очереди переживают рестарт AI. Если цена так и не появилась, вызов остаётся с состоянием `unavailable`, а не с нулевой ценой. Прямой Groq STT или Deepgram TTS без возвращённой цены также остаются неполными.

AI возвращает CMP число фактических попыток STT, LLM и TTS вместе с результатом обработки. CMP сохраняет его только для доставленного голосового обмена. Обмен считается полностью оценённым, если число сохранённых вызовов совпадает с ожидаемым **по каждому компоненту** и у всех них известна цена. Отсутствующий счётчик вызовов не даёт показать 100% покрытия. Это защищает от ложного полного покрытия при потере одной записи или при несовпадении версий AI и CMP.

Grafana и `/admin/metrics` читают итог из PostgreSQL:

- **Стоимость успешного голосового обмена** = сумма известных цен вызовов по доставленным обменам / число этих обменов за выбранный период. При неполном покрытии это нижняя граница.
- **Обменов с полной ценой** = доля доставленных обменов, для которых учтены все ожидаемые вызовы STT, LLM и TTS и известны все цены.
- **Неизвестные цены и расхождения** = вызовы без цены плюс несовпадения числа записей с ожидаемым числом вызовов. При отсутствии ожидаемых счётчиков обмен отмечается неполным.
- **Стоимость успешного обмена по дням** = та же стоимость за московские календарные сутки доставки. Поздняя цена TTS исправляет точку исходного дня.
- **Начало полного учёта** = дата первого доставленного обмена, для которого AI передал ожидаемые числа вызовов всех трёх компонентов.

Ранее собранные Prometheus-счётчики остаются операционными, но не используются для новой цены обмена: их нельзя поправить задним числом после поздней цены TTS. До первого выката новой схемы данные не восстанавливаются автоматически. Дата первого учтённого обмена в PostgreSQL — фактическое начало этой истории.

## Изоляция технических прогонов

Clip с `X-Speaky-Load-Test: 1` исключается из продуктовых счётчиков AI, стоимости и voice journey;
не записывает пользовательский диалог и audit artifacts. Другое значение заголовка означает обычный ход.
Генераторы и тестовые fixtures находятся в `load/`; старые результаты не задают текущую пропускную способность.

## Clip error dashboard

The later [технический контракт](analytics.md#сквозная-диагностика-голосовых-ошибок) adds user-visible delivery outcomes. The AI-job counters described below retain their original definition.

Both `/admin/metrics/errors` and the local-port `/admin/monitoring/errors` use the same **Ошибки** page. The first uses the existing metrics password cookie; the second follows the monitoring port restriction from PR #31. It shows completed clip jobs only: successful jobs, pipeline timeouts, and other pipeline failures. The daily failure rate is `failed / (successful + failed)`. The day boundary is Europe/Moscow.

AI service records one result per completed job, including onboarding clips. Redis stores daily hashes under `metrics:clip-results:YYYY-MM-DD` with a 30 day TTL; the in-memory store uses the same fields for local development. The dashboard reads the most recent 14 days. A job is successful when the clip or onboarding turn returns; an exception is `pipeline_failed` (or the existing `onboarding_stt_failed`), and the whole-job timeout is `timeout`. The current pipeline stage is recorded as STT, LLM, TTS, dialogue state, metrics, or onboarding. Concurrent processing can make the stage approximate on a timeout. Failures caught inside the pipeline (such as the optional streak update) do not make the job fail.

The page also shows the latest 15 failures from a bounded Redis list (`metrics:clip-errors:recent`, 30 day TTL). Each entry has Moscow time, the username already held in the metrics profile when the failure occurred (or `—`), the numeric Telegram chat ID extracted from the session ID (or `—` for other clients), stage, code, and a short exception class/message. In a private Telegram chat, that chat ID identifies the user; for a group it identifies the group. The message is flattened to one line, limited to 240 characters, and common token formats and Telegram session IDs in exception text are redacted before storage. HTML is escaped on output. Entries contain no transcript or raw session ID, but the numeric Telegram ID, username, and provider exception text should still be treated as sensitive admin data. Earlier error entries without an ID or username cannot be attributed retroactively. Recording a metric is best effort and cannot turn a completed job into a failure.

This page does not count rejected uploads, Telegram delivery errors, realtime calls, reviews, or reminders. Those flows need their own instrumented outcomes before they can be included. Older days before this counter was enabled show zero; they cannot be reconstructed from existing metrics.

## Сквозная диагностика голосовых ошибок

AI job counters и результат доставки голоса имеют разные знаменатели.

### User-visible result

The Telegram section of `/admin/metrics/errors` and `/admin/monitoring/errors` counts unique `(chat ID, message ID)` voice attempts by their Moscow receive day. `delivered` means Telegram accepted the main audio message. A successfully sent text fallback is `text_fallback`, a failed voice outcome. Prompts issued before the voice becomes eligible are `not_eligible` and excluded from the failure denominator. A still pending attempt appears as `unknown` after three minutes; this is derived at read time so it stays visible after process loss. Technical onboarding Retry does not create a second received voice attempt. Group chat IDs identify chats rather than individual users.

User-visible failure rate is `failed eligible terminal / all eligible terminal`. The page also shows pending and not-eligible counts, stage/reason counts, p50/p95 by outcome and stage with sample sizes for today and the 14-day period, unique affected chats, and chats with at least three consecutive failures. A delivered answer resets that streak. The last 15 failures retain username, Telegram chat ID, outcome, stage, reason, and a stable attempt ID. The AI section retains its separate completion-day counters, recent exception text and job ID.

### Collection and storage

`VoiceAttemptRecorder` queues bounded, best-effort writes outside the reply path. It reports failed/overflowed writes. The webhook process owns the recorder and drains it briefly on shutdown. Flyway V6 stores a row per message in Postgres, with unique chat/message keys, an opaque deterministic UUID, safe username snapshot, terminal outcome, job ID, and bounded stage durations. Null intermediate fields are retained by later writes; the first terminal outcome wins. A local in-memory implementation uses the same report calculation. A background task prunes rows older than 30 days in small batches. The report reads only the last 14 days and at most 50,000 rows; if it hits the cap, the page states that aggregates are incomplete. No old event history is backfilled.

The attempt ID is an optional internal clip form field. The AI job records it and its job ID in logs and recent failures. The Ktor client reads AI stage timings from the clip status; failed jobs also expose timings observed before failure. The stage durations overlap where work runs concurrently, so they must not be summed to derive end-to-end time. Telegram setup, chat queue, clip queue, download, processing, delivery, and total use monotonic clocks.

The Telegram Prometheus operational metrics from the combined monitoring branch run alongside these Postgres attempt records. Both use the same chat-action wait duration and Telegram delivery result. Prometheus continues to count a successfully sent text fallback as a delivered Telegram message; the error dashboard classifies that same attempt as a failed voice outcome (`text_fallback`). Neither series is substituted for the other.

AI-service stores daily bounded reason, provider attempt/final-operation, and partial feature counters in Redis with 30-day TTL, plus bounded recent failure lists. Provider labels are limited to Groq, Deepgram, OpenRouter, OpenAI and unknown; no provider URL or model text becomes a metric key. The existing `metrics:v2:events` list is capped at 10,000 and expires after 30 days; v2 daily hashes/sets also expire after 30 days. A retry failure followed by success increments an attempt failure and a final operation success, without counting a failed voice turn. Corrections keep their existing terminal outcomes and attempt counts.

Partial feature counters are **AI operation counters**, not proof that the corresponding main voice reached Telegram. They include corrections, streak, call summary/turn, onboarding assessment/review/verification/closing, and optional Telegram follow-up cards. Optional card delivery failure is logged and counted while the main audio send continues. For onboarding, a text fallback is a failed voice outcome even if text delivery succeeds. Recent partial examples contain only time, fixed feature name and bounded reason; no transcript, audio, generated text or raw provider response enters these new stores.

### Availability and validation

The two error routes retain their existing access rules. They load Telegram/Postgres and AI/Redis sections independently, showing unavailable rather than zero when a source fails. The counters begin at deployment and cannot reconstruct earlier failures. Local verification covers Python, Ktor, Flyway and JDBC on temporary Postgres; real Telegram delivery, provider classifications and runtime overhead still need DEV validation before rollout.
