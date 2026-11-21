# Ручные проверки DEV

Запускайте workflow с защищённой `main` на уже работающем DEV. Workflow используют environment `deploy-dev`, те же SSH secrets и host keys, что Redeploy DEV. Операторские запуски идут в общей DEV очереди.

| Workflow | Inputs и результат |
| --- | --- |
| [DEV acceptance](../../.github/workflows/dev-acceptance.yml) | Введите полные `ktor_revision` и `ai_revision`. Для `text` передайте синтетический `learner_message` и при продолжении `student_id`. Для `foundation` задайте файл `foundation-*.json`. Для `foundation-entitlement` задайте `student_id`. Для `voice` задайте сценарий, `student_id` и принадлежащий ему `lesson_id`. |
| [DEV diagnostics](../../.github/workflows/dev-diagnostics.yml) | `readiness` проверяет оба сервиса и зависимости без модели. `correlation-probe` отправляет один невалидный Mini App запрос и сверяет запись отказа между AI и Ktor. `ai-log` ищет по `error_id` или `call_session_id`. `ktor-log` использует `log_at` и необязательный `foundation_job_id`. Укажите полные SHA затронутых сервисов. |
| [DEV speech benchmark](../../.github/workflows/dev-speech-benchmark.yml) | Укажите `ai_revision` и подтвердите `allow_paid_stt`. Бенчмарк использует работающий AI-образ и выполняет один прогрев и десять измерений STT. |
| [Import or publish reviewed DEV catalog](../../.github/workflows/catalog-dev-release.yml) | Введите точную версию и SHA-256. Для публикации добавьте `publish`, `reviewed_by` и `review_reference`. |
| [DEV test config](../../.github/workflows/dev-test-config.yml) | `enable` применяет выбранные `agent_test` и `billing_mock` до истечения `duration_minutes`, от 5 до 90. `disable` снимает оба временных флага. Укажите полные SHA Ktor и AI. Требуется [таймер на хостах](infrastructure-maintenance.md#таймер-dev-test-config). |

## Запуск приёмки

Включите `agent_test` с запасом времени до конца прогона. Подтвердите `allow_paid_model_calls` для `text`, голосового сценария с моделью и Foundation-сценария с командами. Один запуск выполняет одну попытку. После ошибки проверьте сохранённый результат и запросите отдельное разрешение перед платным повтором. Для чтения голосового состояния укажите сценарий `read-only`.

Dispatch inputs видны в GitHub. Передавайте только синтетические тексты и идентификаторы. Workflow пишет подробные результаты в каталог `/tmp/speaky-acceptance.*` с правами `700` на DEV-хосте Ktor. В логе Actions указан путь. Читайте файлы через разрешённый SSH-доступ и удалите каталог после разбора. При повторном запуске используйте новый каталог. Хостовая очистка временных файлов может удалить результаты раньше.

## Подготовка controlled audio

Сценарий `regular-action-controlled-input` синтезирует PCM в offline job без deploy environment. Job публикует артефакт с шифрованием AES-256-GCM и RSA-OAEP, на один день. Живой job расшифровывает его внутри своего runner и проверяет manifest и PCM до запроса на DEV.

Создайте отдельную пару RSA ключа и X.509 сертификата для этой операции. Поместите сертификат PEM в repository variable `SPEAKY_DEV_AUDIO_RECIPIENT_CERTIFICATE`. Поместите приватный ключ PEM в secret `SPEAKY_DEV_AUDIO_RECIPIENT_KEY` environment `deploy-dev`. Храните ключ только в закрытых системах.
