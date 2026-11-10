# Проверки сохранённых подсистем

Перенесены фактические результаты из удалённых планов. Это свидетельства своих ревизий, не новая приёмка или roadmap.

## Разделение personality/mode и voice history, 2026-10-31

На этапе отделения conversation adapter прошли 492 Python tests. Targeted Kotlin tests
не дошли до выполнения: Gradle остановился на конфигурации.
После миграции free history прошли 504 Python tests, включая parallel migration,
retention, deduplication, retry identity и dual-write expiry.
Живая беседа с переключением персон тогда не была проверена.
Текущий контракт: [voice runtime](../integrations/voice-runtime.md).

## 2026-10-01-direct-deepgram-tts

Status: implemented locally; 196 AI tests and `:server:test` pass. DEV rollout and live Telegram latency check pending.

## 2026-10-19-onboarding-nudge-analytics

Implemented locally. `:server:test` passed: 219 tests, zero failures/errors/skips, with `TEST_POSTGRES_URL` set to a fresh disposable PostgreSQL 14 database. `:server:detekt` and `git diff --check` passed. The migration and JDBC queries ran against PostgreSQL, including delayed acknowledgement near the seven-day boundary and practice following a non-primary attempt. DEV/PROD deployment, live Telegram delivery and browser visual acceptance have not been performed.

## 2026-10-04-telegram-grafana-scaling

Status: implemented locally on the PR #31 branch; DEV and PROD rollout not performed. Python tests: 267 passed. `./gradlew :server:test detekt`: passed. Scope: extend the Telegram-only Grafana dashboard in PR #31.

## 2026-10-02-telegram-vocabulary-alternatives

The local Python suite passed 385 tests. Focused Kotlin Telegram tests and detekt passed. Isolated provider probes on four synthetic turns with the first prompt returned zero alternatives. A two-pass prompt with three concrete positive examples returned alternatives on three relevant turns and none on the natural-control turn. It found an error and an independent alternative in the same sentence. An error-focused mixed verifier rejected both good alternatives; a separate alternative verifier accepted both in an isolated probe. In a follow-up, the verifier initially accepted an unsupported `make do with` option. An explicit resource-constraint rule made it reject that option while retaining the valid `on the side` option and rejecting a stage-changing `launch` rewrite. These requests used the configured PROD provider but did not create or change chats, call records, or assessments.

## 2026-10-08-onboarding-assessment-token-limit

Status: implemented locally; 297 AI-service tests passed. DEV and live Telegram
validation are pending. No deployment is included in this change.

## 2026-10-24-telegram-speech-time-timers

Локально проверено: 458 Python тестов; `:server:test :server:detekt`; JDBC тесты на
временной PostgreSQL базе. К десяти LibriSpeech OGG длиной 4–23 с добавлены паузы:
2 с в начале, 3 с в середине и 4 с в конце. Все десять прошли путь декодирования
и VAD; прибавка к измеренному времени речи исходного файла составила 0,34–0,86 с.
При последовательной обработке OGG с имитацией STT в 0,1 с путь занял 0,11–0,15 с.
Первый прогон с двумя потоками и без очереди дал VAD двум из десяти одновременных
запросов; остальные восемь получили fallback `busy`. Это был лимит допуска, а не
таймаут VAD. После добавления ограниченной очереди и четырёх потоков на тех же
OGG с имитацией STT в 0,1 с VAD получили восемь из десяти; два достигли лимита
ожидания 0,5 с. При имитации STT в 0,3 с VAD получили все десять, добавочное
ожидание составило до 0,49 с. Отдельно проверено, что пустой STT даёт ноль.
Это локальные замеры с имитацией STT, а не время ответа Telegram и не проверка
точности относительно ручной разметки. Требуемые 20 голосовых и проверка DEV Telegram
остаются открытыми; критерии точности и задержки пока не подтверждены.

## Исторические проверки удалённых путей

Следующие свидетельства перенесены из прежних планов. Они относятся к старому коду,
не подтверждают новый чат, текущий DEV или качество модели. Новых runtime-тестов при очистке не было.

| Источник удалённого плана | Историческое свидетельство | Ограничение |
| --- | --- | --- |
| Voice onboarding, 2026-09-28 | После result slides: 95 Python tests и `:server:test` прошли | Модель и Telegram не проверялись |
| Voice onboarding, уточнение conservative screening | 118 Python tests, `:server:test detekt` прошли | Реальная точность screening и Telegram не проверялись |
| Incomplete onboarding nudge, 2026-10-06 | `:server:test`, `:server:detekt`, PostgreSQL integration test во временной БД прошли | Живая отправка не проверялась |
| Personal timing, 2026-10-20 | Server/PostgreSQL checks прошли; V17 расширяла ledger PR #40 | DEV Telegram acceptance и deploy тогда оставались открытыми |
| Stage followups, 2026-10-20 | Автоматические проверки прошли | DEV Telegram acceptance и deploy тогда оставались открытыми |


## Kora provider transport, 2026-10-20–23

20 октября: рабочая ветка — 396 Python / 212 Kotlin tests; release base — 390 / 250.
Изолированный Sonnet → EVI → Opus занял 18.3 с; DEV-container reply pipeline — 16.8 с.
Синтетическая проверка использовала in-memory state, без Telegram и пользовательской аналитики.
22 октября OpenRouter Gemini 3.8 возвращал PCM; WAV/MP3 отклонялись HTTP 400.
Конвертация дала валидный OGG на трёх скоростях. Gemini 3.1 также вернул PCM,
а adapter probe — OGG на скорости 0.9. Эти пробы подтверждали transport/encoding,
но не качество голоса, точность произнесённого текста или полный Telegram call.

## Spoken correction probes, 2026-09-30–10-02

On 2026-09-30, two complete Luna-only passes on the 100 synthetic cases each had zero false-correction cases and zero missed-positive cases. The final pass had two edit mismatches that are valid alternatives (`an advice` → `some advice`; `suggested me to` → `suggested that I`) and one context mismatch that retained a spoken `um` while showing the full error. A first targeted repeat exposed a false `the the` card in three of four calls and two missed complete conditionals; after the prompt clarification, four repeats of each risky negative, two of each risky positive, and 27 calls on nine unseen variants matched their labels. These are small synthetic samples, not a measured production error rate. The separate live reviewer was removed; ordinary turns now use one Luna call for the reply and one Luna call for corrections, in parallel. Final onboarding assessment verification remains separate.

On 2026-10-02, a further 100-case live pass through the new two-attempt, 8-second path returned zero labeled false-correction cases and zero missed-positive cases, but manual inspection found the unsafe `can to swim` → `swim` edit described above. Aggregate labels alone therefore do not establish semantic safety. The modal-preservation gate was added after this run; it has a focused regression test, while the full live pass has not been repeated with the new gate.
