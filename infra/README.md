# Local infra

```bash
cd infra
docker compose up --build
```

Поднимаются:

- `server` — Ktor без Telegram, http://127.0.0.1:8080/health , Swagger UI http://127.0.0.1:8080/swagger
- `ai-service-stub` — мок clip/session API, http://127.0.0.1:8090/health

Telegram-бот работает только через webhook (TLS + `TELEGRAM_WEBHOOK_URL` на VPS).

Остановка: `Ctrl+C` или `docker compose down`.
