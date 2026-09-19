# ai-service

Python FastAPI service that turns a Telegram voice clip into a spoken English reply:

`STT (Groq) → LLM (OpenRouter / OpenAI models) → TTS (OpenRouter)`

Clip contract (`HttpClipClient` / `ClipDtos`):

- `POST /v1/sessions` → 201 `{ sessionId, greeting.text }`
- `GET /v1/sessions/{sessionId}/greeting/audio`
- `POST /v1/clips` (202) → poll `GET /v1/clips/{jobId}` → `GET /v1/clips/{jobId}/audio`

`/v1/*` needs header `X-Internal-Token` matching `AI_INTERNAL_TOKEN`. `/health` is open. Swagger/OpenAPI is off.

## Local run

```bash
cd ai-service
python3 -m venv .venv
source .venv/bin/activate
pip install -r requirements-dev.txt
export GROQ_API_KEY=...
export OPENAI_API_KEY=...
export AI_INTERNAL_TOKEN=...
# OpenRouter, default in the app: OPENAI_BASE_URL=https://openrouter.ai/api/v1
uvicorn app.main:create_app --factory --host 127.0.0.1 --port 8090
```

On two hosts, Ktor’s `AI_SERVICE_BASE_URL` is `http://<ai-server>:8090` (deploy publishes 8090 on all interfaces). Same-machine leftover: `http://127.0.0.1:8090`. Redis stays loopback.

```bash
pytest
```

## Docker

Image is `ai-service/Dockerfile`. Runtime on the server is Redeploy (`infra/ai-service/deploy-remote.sh`), not local compose.

Redeploy starts Redis on the server before each ai-service deploy (`infra/redis/deploy-remote.sh`: create if missing, otherwise leave it). Do not recreate Redis with `docker rm`. The Python container joins Docker network `speaking-coach` and uses `REDIS_URL` from that host's `.env` (prod script default `redis://redis:6379/0`).
