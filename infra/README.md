# Infra

VPS deploy scripts. Local Docker Compose and `ai-service-stub` are gone — integration is Redeploy to DEV hosts.

- `server/` — Ktor fat JAR image (`Dockerfile.runtime`) and `deploy-remote.sh` (`/opt/speaking-coach`)
- `ai-service/` — Python container deploy, 8090 allowlist
- `redis/` — create-if-missing Redis on the AI host; never `docker rm redis`

Канон: [`ai_docs/deploy.md`](../ai_docs/deploy.md).
