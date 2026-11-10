# SP-00 — реализация и проверка

Статус: **согласованный фундамент реализован и локально проверен; серверное переключение не выполнено**.
Основание: [согласованный план](2026-11-10-sp00-foundation.md), включая последующее решение изолировать legacy.
Базовая git-ревизия: `60d4085`; результаты ниже относятся к незакоммиченным изменениям поверх неё.
Commit/push/deploy, платные модели и Telegram-отправки не выполнялись.

## Итоговый объём

Пользователь отменил перенос legacy calls, Vocabulary Builder, reminders и аналитики в PostgreSQL.
Их исходники сохранены, но новый runtime не регистрирует их routes/handlers/workers и не создаёт Redis clients.
Переносится только текущий TutorStore. Серверные Redis/PostgreSQL данные и volumes не менялись.
Ранее созданные промежуточные legacy adapters и таблицы сохранены вне shipping composition roots.
Они не являются вторым активным владельцем данных Speaky и не требуют дальнейшего переноса в SP-00.

## Рабочие границы

| Компонент | Ответственность и реальная связь |
| --- | --- |
| `foundation/` в Ktor | Единственный владелец Student/identity, profile/goal/memory, Lesson/Call Session, Schedule, payment/entitlement, Roadmap, Plan/Evaluation, history и jobs в PostgreSQL |
| `FoundationService` | Версионированные commands/snapshots, scoped identity/roles/source ownership, версии, receipts, короткие транзакции |
| `FoundationChat`, `ConversationRuns`, `TelegramTextIntake` | Сохранение private-text входа до ACK, FIFO/lease/fence, сохранённый reply, recovery после tool commit, учёт доставки |
| `StructuredMemory`, `FoundationMaintenance` | Личные AI candidates скрыты до подтверждения; учебные observations с evidence; 30-day cleanup, forget/delete/epoch и внешний deletion ledger |
| `FoundationJobs`, `FoundationWorker`, `BackgroundContracts` | Durable claim/retry/lease/fence; compute вне DB transaction; apply с проверкой актуальности; explicit schedule/reminder contracts |
| Python `FoundationTutor` | Существующая persona/native tool schema; бизнес-изменения только через backend commands; независимого изменяемого профиля нет |
| Python `ContextBuilder` | Общие bounded projections для text/voice/learning, текущая цель, подтверждённые факты, observations, Roadmap и готовность Lesson |
| Python `LessonVoice`, `LearningEngine` | Передача общего context в session instructions; result computation и применение через scoped backend; будущий transport/pedagogy не подменены заглушкой успеха |

Shipping roots: `Application.kt` → `SpeakyApplication.kt`; AI Docker → `app.speaky_main:create_app`.
Текущий текстовый чат подключён к новому foundation. `app.main` и прежний Kotlin composition root сохранены как legacy.
Старые HTTP adapters доступны только в test harness; harness не входит в fat JAR.

## Импорт и эксплуатация

- `scripts/export_tutor_state.py`: frozen TutorStore-only manifest, точные bytes/checksums, identity validation,
  двойная проверка snapshot, новый файл 0600. Writers останавливает оператор, не exporter.
- `foundationImport`: проверка всех entries, идемпотентные per-student receipts/checkpoints, сверка набора и количества,
  cutover marker после завершения. Повтор того же файла безопасен; изменённый source блокируется.
- Сохранены текущие goal/facts, booking IDs, messages/replies, receipts/cards. Исходные 7-day deadlines импорта
  не продлеваются до 30 дней. Полная прежняя история версий профиля не восстанавливается как новый журнал revisions.
- Новый backend не стартует без marker. Пустой manifest разрешён только для действительно пустого target.
- `foundationMaintenance`: diagnostics, безопасный retry-job, export/restore deletion ledger.
- Неизвестная доставка Telegram/reminder не повторяется вслепую. Это не обещание exactly-once внешней доставки.
- Реальный `pg_dump`/`pg_restore` проверен с последующим replay более свежего внешнего deletion ledger.
- Runbook передан для включения в [единый operational-справочник](../integrations/operations.md).

Новые миграции V26–V38. Старые V1–V25 не переписывались. Промежуточные legacy-миграции уже применялись
к локальным тестовым схемам и сохранены без изменения; удаление их таблиц не является частью этой задачи.

## Acceptance → проверяемое доказательство

Тесты не равны один-к-одному номерам IT: один сценарий покрывает несколько контрактов.
Kotlin имена ниже находятся в `FoundationIntegrationTest.kt`; Python — в `integration/test_foundation_http.py`.

| AC / IT | Реализованный результат | Конкретное доказательство |
| --- | --- | --- |
| Компоненты/интерфейсы, IT-01 | Версионированный Ktor API и Python client, scoped HTTP, отдельные roots | `test_shipping_python_app_uses_real_backend_scope_and_native_tools`; `capabilitiesVersionsAndReceiptsPreventCrossStudentWritesAndRepeatedChanges` |
| Сущности/связи, IT-02 | Реальные FK/версии и операции, включая Lesson/episodes, Schedule, entitlement, Roadmap, evaluation/plan | `confirmedPaymentIsIdempotentAndDoesNotOwnTheSchedule`; `scheduleHandlerMaterializesOneOccurrenceAndCannotResurrectACancellation`; центральный state test |
| Общий state, IT-03 | Text сохраняет цель, Voice читает, Learning возвращает observation; поздняя работа не возвращает старую цель | `test_text_tool_commits_in_ktor_and_voice_reads_same_goal`; `test_learning_result_returns_to_chat_without_reverting_a_new_goal`; `chatGoalAndLessonObservationShareStateWithoutAllowingAnOldJobToRewriteTheGoal` |
| Memory/Context, IT-04 | User facts отдельно от hidden candidates; подтверждение источником; observation evidence | `personalCandidatesRemainHiddenUntilAUserConfirmsTheirSource`; `test_memory_extraction_commits_only_hidden_candidates_from_its_durable_source`; центральный state test |
| Tools, IT-05 | Identity/scope/source/version/idempotency принадлежат backend | `capabilitiesVersionsAndReceiptsPreventCrossStudentWritesAndRepeatedChanges`; `recoveredRunReturnsCommittedBookingInsteadOfRepeatingTheInstruction` |
| Jobs, IT-06 | Lease/fence, retry, dependency/version validation; compute вне транзакции; diagnostics | `expiredWorkerCannotCommitAndRetentionDoesNotRenewSourceDeadlines`; `workerRenewalExtendsOnlyItsLiveFencedCapability`; `workerUsesBoundCapabilityAndCommitsPreparationOutsideTheClaimTransaction` |
| Schedule/reminder contracts, IT-07 | Explicit materialization, cancelled/stale protection, delivery receipt/unknown protection | `scheduleHandlerMaterializesOneOccurrenceAndCannotResurrectACancellation`; `reminderTestAdapterRunsOnceAndRecordsItsDeliveryReceipt`; `reminderContractSkipsCancelledLessonsAndNeverResendsUnknownDelivery` |
| Retention/delete, IT-08 | Source deadline не обновляется копированием; forget стирает прошлую цель; stale scope запрещён; ledger после restore | `expiredWorkerCannotCommitAndRetentionDoesNotRenewSourceDeadlines`; `forgettingGoalErasesHistoricalRevisionsAndInvalidatesOldScopes`; `realBackupRestoreReplaysDeletionLedgerBeforeServingContext` |
| Chat/cutover, IT-09 | Durable input/reply, FIFO recovery, сохранённый committed tool, неизвестная доставка | `telegramBridgePersistsInputAndDeliveryAndRejectsAGroupIdentity`; `telegramIntakeIsDurableOrderedAndRecoverableAfterAnExpiredDispatch`; `uncertainTelegramDeliveryIsNotRetriedBlindly`; recovered booking test |
| Readiness/capture, IT-10 | Voice context только после backend admission; source revisions/dedup; stale plan блокируется | `test_voice_transport_receives_context_only_after_backend_admission`; центральный state test; worker preparation test |
| Isolation, IT-11 | Старые roots не запускаются; legacy routes отсутствуют | `isolatedShippingRuntimeRequiresCutoverAndRegistersNoLegacyRoutes`; `tests/test_speaky_runtime.py` |
| TutorStore import, IT-12 | Настоящие Redis→export→Ktor→PG; повтор/changed source/expiry; manifest gate/checkpoints | `integration/test_tutor_import_redis.py`; `cutoverRejectsCorruptManifestAndCompletesOnlyAfterAllTutorImports` (включая прерывание после per-student receipt до marker) |
| Redis-free, IT-13 | Shipping deps без redis; startup/auth/routes проверены; основной PG/HTTP suite не требует Redis | Fresh runtime-only venv + `test_speaky_runtime.py` с запретом любого Redis import; 5 HTTP tests; Kotlin isolation test; runtime/deploy graph изменён |

Центральное доказательство распределено между тестами transport/intake и state roundtrip.
Один тест не симулирует полный живой Telegram→audio урок. Внешние Telegram/media/model boundary заменены;
PostgreSQL, Redis источника, HTTP, serialization, scopes, worker и backend apply настоящие.

## Результаты проверок

- Python regression: **524 passed**, 1 warning.
- Kotlin regression: **277 passed**, 0 skipped.
- Foundation PostgreSQL: **19 passed**, 0 skipped (включая реальные backup/restore).
- Python↔Ktor↔PostgreSQL runtime: **5 passed**.
- Отдельный real Redis TutorStore import rehearsal: **1 passed**. Временный Redis после проверки остановлен.
- Чистый Python runtime-only venv: Redis package отсутствует; startup/health/auth/route isolation прошли.
- Итоговые `:server:foundationIntegrationTest :server:test :server:buildFatJar detekt` после последних правок: **BUILD SUCCESSFUL**.
- Shell syntax и `git diff --check`: прошли.

Runner: `scripts/test-foundation.sh`, CI: `.github/workflows/foundation.yml`.
`FOUNDATION_TEST_SUITE=migration` требует отдельного `SP00_IMPORT_REDIS_URL`; основной suite его не использует.
Локально: PostgreSQL 14.10 на 55432, synthetic database/user `sp00`; test Redis 7.2.4 на 56379.
CI настроен на PostgreSQL 16 и Redis 7.2 для migration suite; удалённый CI здесь не запускался.

## Явные ограничения

- Полные lesson analyzer/planner, календарная логика и reminder sender не реализованы. Их контракты и test adapters
  исполнимы; production handler сообщает unavailable. Это согласованный scope SP-00, не готовый урок.
- Mini App/UI, платежные правила/grace, расписание продукта, полноценный voice UX остаются SP-01–SP-08.
- При отсутствии нужного анализа/плана будущая встреча блокируется, как выбрано в интервью.
- Forget консервативно очищает raw history нового пути, поскольку prose может содержать косвенные копии факта.
  Сохранённые legacy данные не обещают очистку через новый deletion ledger.
- Оператор должен хранить свежий deletion ledger отдельно от backup и replay до запуска восстановленного runtime.
- Настоящий cutover, проверка Telegram/model behavior, voice transport/backpressure и DEV readiness не выполнялись.
  Тесты с реальными хранилищами не объявляются живой продуктовой приёмкой.
