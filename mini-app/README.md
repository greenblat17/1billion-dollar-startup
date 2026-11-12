# Speaky Mini App

Самостоятельный frontend Telegram Mini App. HTML/CSS/JS, Web Audio и AudioWorklet.
AI-сервис не содержит и не раздаёт UI. CMP-прототип находится отдельно в `cmp/app`.

```sh
cd mini-app
npm test
npm run build
```

Node 22+, внешних npm-зависимостей нет. `src/` — исходники; `dist/` — независимый артефакт.
`dist/version.json` содержит revision и хеши файлов. Dockerfile раздаёт артефакт через nginx:8080.
API paths `/miniapp/api`, `/miniapp/diagnostics` и WebSocket `/miniapp/voice` обслуживает AI.
HTTPS edge разделяет UI и API на одном origin, поэтому CORS и новые credentials не нужны.

Поставка: GitHub Actions **Deploy Mini App DEV** или флажок `mini_app` в **Redeploy DEV**.
Используются существующие DEV AI SSH secrets только для входа на хост; контейнер frontend не получает `.env` или ключи.
Первый deploy переключает только Mini App vhost существующего Caddy; остальные сайты сохраняются.
Frontend поставляется до AI при совместном redeploy. При первой миграции не выкатывать AI отдельно:
после удаления старых static routes UI должен уже обслуживаться контейнером `mini-app`.

Для UI-only deploy AI/backend не перезапускаются. Откат — предыдущая версия workflow по нужному git ref.
DEV public URL остаётся прежним. PROD frontend этим workflow не обслуживается.


My Progress: `?view=progress` или Telegram start_param `progress` открывает roadmap без микрофона,
WebSocket и создания Lesson. `POST /miniapp/api` с `action=progress` и подписанным initData
возвращает принадлежащую ученику backend-проекцию; подписка для просмотра не нужна.
Состояния: ready, no_roadmap, preparing, insufficient_evidence, error; этапы completed/current/
upcoming/needs_review. Кнопка Telegram появляется со следующим обычным ответом репетитора.
Просмотр не запускает восстановление старых roadmap jobs или платные вызовы.

Контракт урока и прогресса — в [архитектуре](../architecture.md), поставка и визуальная приёмка — только в [статусе](../ai_docs/status.md).
