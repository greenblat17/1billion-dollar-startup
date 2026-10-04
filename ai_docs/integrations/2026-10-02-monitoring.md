# Monitoring

Status: the draft `/admin/metrics` stays. The operator page is `/admin/monitoring`.

`/admin/monitoring` is Ktor HTML on the CMP host. It reads `GET /internal/metrics` from ai-service. The `v2` object is the client cut. Telegram, Android, iOS, and desktop are separate columns. A session without `android`, `ios`, or `desktop` is `unknown`. There is no combined mobile total and no ruble-per-turn card. Empty provider money is a dash.

The public TLS connector does not serve `/admin/monitoring`. Ktor listens for it on `127.0.0.1` and `MONITORING_PORT` (default 8081). Every route is hidden from OpenAPI. There is no `MONITORING_PASSWORD`. `METRICS_PASSWORD` still guards the draft.

Grafana is the outside HTTPS door, on port 8443, with anonymous access off and one admin login. Nginx checks `GET /api/user` and then proxies `/admin/monitoring` to the localhost Ktor port. Prometheus and the blackbox exporter stay on localhost. Blackbox probes `/health` every 15 seconds. Prometheus scrapes `metrics:v2` every 60 seconds and sends `AI_INTERNAL_TOKEN` as `Authorization: Bearer`. The service accepts that header on internal routes as well as `X-Internal-Token`. Day metrics are not stored in Prometheus as a second database; the scrape is the chart history.

The **Speaky** Grafana dashboard shows only series with `client="telegram"`. It omits the shared availability panel and the link to the all-client operator page. Collection and the separate `/admin/monitoring` page still retain the other clients.

New counters use the Redis prefix `metrics:v2` on the AI host. Old `metrics:day` and `metrics:dau` are left as they are and are not shown as a client column. Money is OpenRouter `usage.cost` and TTS `total_cost`, stored as micro-units plus currency. Realtime usage is tokens from `response.done`. Groq stays seconds. A missing cost increments the call count only.

The CMP app sends `platform` on `POST /v1/sessions`. Telegram action names are posted to `POST /internal/metrics/action`. Journal text and voice objects are not in this cut.
