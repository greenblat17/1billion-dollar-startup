# Deploy

Как Speaky попадает на серверы. Секреты в документе **не** перечислять значениями — только имена GitHub secrets. Локальный compose — не прод; см. `integrations/2026-09-18-run-and-ci.md`.

## Что крутится на машине

Три Docker-контейнера. **Prod** сегодня часто один сервер (Ktor host-сеть + AI/Redis на той же машине). **DEV** — два независимых VPS: так моделируем прод, где Ktor и AI **не** обязаны быть у одного провайдера и без LAN датацентра.

| Контейнер | Образ | Сеть | Назначение |
| --- | --- | --- | --- |
| `speaking-coach` | `speaking-coach:local` | **host** | Ktor, порт из `SERVER_PORT` в `.env` (обычно 443), Telegram webhook |
| `postgres` | `postgres:16-alpine` | loopback | пользователи app API, **127.0.0.1:5432**, volume `speaking-coach-postgres` (хост Ktor) |
| `ai-service` | `ai-service:local` | `speaking-coach` | FastAPI, порт **8090** на всех интерфейсах |
| `redis` | `redis:7-alpine` | `speaking-coach` | диалог, **127.0.0.1:6379**, volume `speaking-coach-redis` |

Ktor смотрит на ai-service через `AI_SERVICE_BASE_URL` — адрес, который виден **с машины Ktor** (публичный IP/DNS другого провайдера), не `127.0.0.1` и не VPC Timeweb. Хост-порт FastAPI — `AI_SERVICE_PORT` в `.env` на AI (по умолчанию 8090); скрипт делает `-p $AI_SERVICE_PORT:8090`. В URL Ktor тот же порт. Docker публикует его на всех интерфейсах, а `restrict-8090.sh` режет чужие source в `DOCKER-USER` / `INPUT`: пускает loopback и адреса из `/opt/ai-service/8090.allow`. Без файла снаружи порт закрыт. Общий LAN между хостами не делаем: он не переживёт разъезд по провайдерам. Позже вместо голого HTTP — TLS или туннель (WireGuard и т.п.). Redis остаётся на AI-хосте, наружу и на Ktor не публикуем. `REDIS_URL=redis://redis:6379/0`.

На диске:

- `/opt/speaking-coach/` — fat JAR, `Dockerfile.runtime`, `deploy-remote.sh`, `.env`, **`tls.crt` / `tls.key` (кладёт человек, CI их не генерирует)**
- `/opt/ai-service/` — `app/`, Dockerfile, requirements, deploy-скрипты, `.env`, **`8090.allow` (кладёт человек: IPv4 Ktor, по одному в строке; CI не пишет)**

Рестарт `ai-service` **не** должен `docker rm redis`. Скрипт `infra/redis/deploy-remote.sh` если контейнер `redis` уже есть — только `start` + connect к сети, volume не трогает.
Рестарт `speaking-coach` **не** должен `docker rm postgres`. Скрипт `infra/postgres/deploy-remote.sh` создаёт контейнер, если его нет; иначе `start`. Без `POSTGRES_PASSWORD` в `.env` Postgres не поднимается (бот без app API).

```mermaid
flowchart LR
  redeploy[Redeploy]
  ktor[speaking_coach]
  postgres[(postgres)]
  ai[ai_service]
  redis[(redis)]
  tls[tls_on_disk]
  redeploy -->|cmp-server| ktor
  redeploy -->|ai-server| ai
  ai -->|redis_if_missing| redis
  ktor -->|postgres_if_missing| postgres
  ktor --> ai
  tls --> ktor
```

**Имена:** семейство хостов — `cmp-server` и `ai-server` (галки Redeploy DEV / Redeploy PROD). SSH и `.env` — repo secrets `DEV_*` или `PROD_*`. Каталог и Docker-имя AI остаются `ai-service`. URL в Ktor — ключ `AI_SERVICE_BASE_URL` внутри блоба `.env`, не отдельный secret.

## Тесты авто, выкат только Redeploy

`push` / `pull_request` по path: **CMP** (`cmp/**`, `infra/server/**`) и **AI service** (`ai-service/**`, `infra/ai-service/**`, `infra/redis/**`) — package и тесты. На сервер не едут.

Выкат — два `workflow_dispatch`, ветка раннер не выбирает:

- [`.github/workflows/redeploy.yml`](../.github/workflows/redeploy.yml) — **Redeploy DEV**, `destination: dev`, secrets `DEV_*`
- [`.github/workflows/redeploy-prod.yml`](../.github/workflows/redeploy-prod.yml) — **Redeploy PROD**, `destination: prod`, secrets `PROD_*`

У каждого две галки `cmp-server` и `ai-server` (обе default on). Снятая галка = job skipped, workflow зелёный. Обе сняты = fail до SSH. С `main` и с PR выкат один и тот же, его запускают эти два workflow. В Deployments попадают только `deploy-prod` и `deploy-dev` (имя environment у deploy-джобы, без required reviewers). Секреты — repository Actions secrets, не Environment secrets.

Concurrency: `ktor-prod` / `ktor-dev` / `ai-prod` / `ai-dev`, `cancel-in-progress: false` (очередь, не cancelled).

### Prod — те же скрипты, префикс `PROD_`

SSH: `PROD_CMP_SERVER_HOST` / `USER` / `SSH_KEY`, `PROD_AI_SERVER_HOST` / `USER` / `SSH_KEY`.

Runtime — непрозрачные блобы (оператор заполняет, CI не парсит ключи):

- `PROD_CMP_SERVER_ENV` → `/opt/speaking-coach/.env`
- `PROD_AI_SERVER_ENV` → `/opt/ai-service/.env` (`REDIS_URL` только здесь; `AI_INTERNAL_TOKEN` один и тот же в обоих блобах)

Пустой блоб — fail на раннере до SSH. TLS PEM уже на диске. Redis не `docker rm`. Смена бота: новый токен внутри `PROD_CMP_SERVER_ENV`, Redeploy PROD с галкой cmp-server, у старого токена `deleteWebhook`. Имя в Telegram — BotFather.

CMP packages на `main` пекут `https://$PROD_CMP_SERVER_HOST`, на остальных ветках — `https://$DEV_CMP_SERVER_HOST`. Это не ключ внутри блоба `.env`.

### Dev — те же скрипты, префикс `DEV_`

SSH: `DEV_CMP_SERVER_HOST` / `USER` / `SSH_KEY`, `DEV_AI_SERVER_HOST` / `USER` / `SSH_KEY`. CMP packages bake `https://$DEV_CMP_SERVER_HOST` off `main`; not a key in `DEV_CMP_SERVER_ENV`.

Runtime — непрозрачные блобы (оператор заполняет, CI не парсит ключи):

- `DEV_CMP_SERVER_ENV` → `/opt/speaking-coach/.env` (для app API ещё `JWT_SECRET`, `DATABASE_URL`, `POSTGRES_PASSWORD`)
- `DEV_AI_SERVER_ENV` → `/opt/ai-service/.env` (сюда же `REDIS_URL`, тот же `AI_INTERNAL_TOKEN`, что у Ktor, и `OPENAI_REALTIME_API_KEY` для mint)

Пустой блоб — fail на раннере до SSH. TLS на DEV кладёт человек.

CMP Android / iOS / Desktop на сервер не едут. Jobs ai-service в памяти при рестарте пропадают; Redis-диалоги остаются.

## Ручной деплой (если файлы уже на хосте)

Положить `.env`, вызвать скрипт. Env на хост не передаём.

```bash
bash /opt/speaking-coach/deploy-postgres.sh
bash /opt/speaking-coach/deploy-remote.sh
bash /opt/ai-service/deploy-redis.sh
bash /opt/ai-service/deploy-remote.sh
```

Проверка: `docker ps` — три имени выше; с хоста Ktor `curl -k https://127.0.0.1/health`; с хоста AI или с Ktor `curl http://<ai-server>:8090/health`.
