# Infra

VPS deploy scripts. Local Docker Compose and `ai-service-stub` are gone — integration is Redeploy to DEV hosts.

- `cmp-service/` — client API fat JAR (`/opt/cmp-service`)
- `telegram-service/` — Telegram bot fat JAR (`/opt/telegram-service`)
- `postgres/` — create-if-missing Postgres on the cmp-service host; never `docker rm postgres`
- `ai-service/` — Python container deploy, 8090 allowlist
- `redis/` — create-if-missing Redis on the AI host; never `docker rm redis`

Канон: [`ai_docs/deploy.md`](../ai_docs/deploy.md).
