# Local infra

```bash
cd infra
docker compose up --build
```

Поднимаются:

- `server` — Ktor без Telegram, http://127.0.0.1:8080/health
- `ai-service-stub` — заглушка clip API, http://127.0.0.1:8090/health

Telegram-бот работает только через webhook (TLS + `TELEGRAM_WEBHOOK_URL` на VPS).

Остановка: `Ctrl+C` или `docker compose down`.
