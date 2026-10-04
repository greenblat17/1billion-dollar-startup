# Monitoring

Status: the draft `/admin/metrics` stays. The operator page is `/admin/monitoring`.

`/admin/monitoring` is Ktor HTML on the CMP host. It reads `GET /internal/metrics` from ai-service. The `v2` object is the client cut. Telegram, Android, iOS, and desktop are separate columns. A session without `android`, `ios`, or `desktop` is `unknown`. There is no combined mobile total and no ruble-per-turn card. Empty provider money is a dash.

The public TLS connector does not serve `/admin/monitoring`. Ktor listens for it on `127.0.0.1` and `MONITORING_PORT` (default 8081). Every route is hidden from OpenAPI. There is no `MONITORING_PASSWORD`. `METRICS_PASSWORD` still guards the draft.

Grafana is the outside HTTPS door, on port 8443, with anonymous access off and one admin login. Nginx checks `GET /api/user` and then proxies `/admin/monitoring` to the localhost Ktor port. Prometheus and the blackbox exporter stay on localhost. Blackbox probes `/health` every 15 seconds. Prometheus scrapes `metrics:v2` every 60 seconds and sends `AI_INTERNAL_TOKEN` as `Authorization: Bearer`. The service accepts that header on internal routes as well as `X-Internal-Token`. Day metrics are not stored in Prometheus as a second database; the scrape is the chart history.

The **Speaky** Grafana dashboard shows only series with `client="telegram"`. It omits the shared availability panel and the link to the all-client operator page. Collection and the separate `/admin/monitoring` page still retain the other clients.

The spend panel converts stored micro-USD into USD in its Prometheus query and formats the result as dollars with six decimal places.

The errors panel uses the Telegram DAU series as a zero fallback because `speaking_errors` has no series until an error occurs. It shows zero while Prometheus receives Telegram metrics; if the scrape is unavailable, it still shows `No data`.

The scaling row uses the existing blackbox `/health` probes, `up` for the AI and Ktor metrics jobs, and the age of the latest Telegram DAU sample. Freshness means a successful scrape, not a recent user action. Ktor exposes `/internal/telegram-metrics/prometheus` only on its localhost `MONITORING_PORT`; Prometheus scrapes it every 15 seconds. AI metrics retain their 60-second scrape. Ktor and AI stage counters are process-local monotonic counters; Prometheus preserves their history across process restarts.

For Telegram voice replies, `telegram_voice_response_duration_seconds` measures from Ktor accepting the update to Telegram acknowledging the final voice or text. `telegram_voice_queue_wait_seconds` adds the waits in `TelegramChatActions` and `SessionClipQueue`. Histograms include only successfully delivered responses; p50 and p95 use the past 15 minutes. The dashboard displays the response sample count separately. `telegram_voice_requests_total` also counts failed and queue-full outcomes, including failures before STT.

`speaking_stage_attempts_total` counts one logical operation after internal retries for STT, LLM, TTS, and reply delivery. LLM includes reply, notes, and onboarding operations; these are stage-level rates, not the percentage of user turns that failed. A no-speech clarification is a successful STT operation. The Grafana failure share is `failure / (success + failure)` for each stage over 15 minutes and has no value when the stage had no attempts. The 5- and 15-minute failure panels use these monotonic counters. The older `speaking_errors` panel remains a daily breakdown and is not an exhaustive HTTP or log error count.

`Ходов на активного пользователя сегодня` divides today's completed Telegram turns by today's Telegram DAU, only when DAU is positive. `Действия за день` remains a daily action count. D1 cohort retention is calculated from activation and daily user events on `/admin/metrics/streaks`, not from Grafana's 24-hour time series.

New counters use the Redis prefix `metrics:v2` on the AI host. Old `metrics:day` and `metrics:dau` are left as they are and are not shown as a client column. Money is OpenRouter `usage.cost` and TTS `total_cost`, stored as micro-units plus currency. Realtime usage is tokens from `response.done`. Groq stays seconds. A missing cost increments the call count only.

The CMP app sends `platform` on `POST /v1/sessions`. Telegram action names are posted to `POST /internal/metrics/action`. Journal text and voice objects are not in this cut.
