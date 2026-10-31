#!/usr/bin/env bash
# Localhost Prometheus and blackbox, plus Grafana on 8443 when a password is set.
# Does not remove the bot container or the Redis volume.
set -euo pipefail

APP=/opt/speaking-coach
MON="$APP/monitoring"
ENV_FILE="$APP/.env"

if [ ! -s "$ENV_FILE" ]; then
  echo "Missing $ENV_FILE; monitoring stack skipped" >&2
  exit 0
fi

env_get() {
  local key="$1"
  local line
  line=$(grep -E "^${key}=" "$ENV_FILE" | tail -n 1 || true)
  printf '%s' "${line#"${key}"=}"
}

SERVER_PORT=$(env_get SERVER_PORT)
MONITORING_PORT=$(env_get MONITORING_PORT)
AI_URL=$(env_get AI_SERVICE_BASE_URL)
TOKEN=$(env_get AI_INTERNAL_TOKEN)
GRAFANA_PASSWORD=$(env_get GRAFANA_ADMIN_PASSWORD)
GRAFANA_ROOT=$(env_get GRAFANA_ROOT_URL)
VOICE_COST_DB_NAME=$(env_get POSTGRES_DB)
VOICE_COST_DB_USER=$(env_get POSTGRES_USER)
SPEAKY_ENV=$(env_get SPEAKY_ENV)
SPEAKY_ENV=${SPEAKY_ENV:-unknown}

ALERT_ENV="$MON/alerts.env"
CREDIT_ENV="$MON/openrouter.env"
ALERT_PROVISIONING="$MON/grafana/provisioning/alerting"
ALERTS_ENABLED=false
if [ -s "$ALERT_ENV" ]; then
  ALERTS_ENABLED=true
  mkdir -p "$ALERT_PROVISIONING"
  cp "$MON/alerts/speaky.yml" "$ALERT_PROVISIONING/speaky.yml"
else
  rm -f "$ALERT_PROVISIONING/speaky.yml"
fi

SERVER_PORT=${SERVER_PORT:-443}
MONITORING_PORT=${MONITORING_PORT:-8081}
AI_URL=${AI_URL:-http://127.0.0.1:8090}
AI_URL=${AI_URL%/}
AI_TARGET=${AI_URL#*://}
AI_TARGET=${AI_TARGET%%/*}
AI_SCHEME=${AI_URL%%://*}
if [ "$AI_SCHEME" = "$AI_URL" ]; then
  AI_SCHEME=http
fi
GRAFANA_ROOT=${GRAFANA_ROOT:-https://127.0.0.1:8443}
VOICE_COST_DB_NAME=${VOICE_COST_DB_NAME:-speaking_coach}
VOICE_COST_DB_USER=${VOICE_COST_DB_USER:-speaking}

if [ -n "$GRAFANA_PASSWORD" ]; then
  # The Grafana account can read only the two cost tables. Its password is
  # generated on the CMP host and never stored in the deployment environment.
  if [ ! -s "$MON/voice_cost_db_password" ]; then
    umask 077
    openssl rand -hex 32 > "$MON/voice_cost_db_password"
  fi
  chmod 600 "$MON/voice_cost_db_password"
  export VOICE_COST_DB_PASSWORD
  VOICE_COST_DB_PASSWORD=$(cat "$MON/voice_cost_db_password")
  export VOICE_COST_DB_NAME
  if docker inspect postgres >/dev/null 2>&1; then
    voice_cost_tables_ready=false
    for attempt in $(seq 1 30); do
      if docker exec postgres psql -U "$VOICE_COST_DB_USER" -d "$VOICE_COST_DB_NAME" -Atqc \
        "SELECT to_regclass('public.voice_cost_calls') IS NOT NULL AND to_regclass('public.voice_cost_exchanges') IS NOT NULL AND to_regclass('public.habit_call_daily') IS NOT NULL" 2>/dev/null | grep -qx t; then
        voice_cost_tables_ready=true
        docker exec -i postgres psql -X -v ON_ERROR_STOP=1 -U "$VOICE_COST_DB_USER" \
          -d "$VOICE_COST_DB_NAME" -v cost_password="$VOICE_COST_DB_PASSWORD" <<'SQL'
SELECT format('CREATE ROLE speaking_cost_ro LOGIN PASSWORD %L', :'cost_password')
WHERE NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'speaking_cost_ro') \gexec
ALTER ROLE speaking_cost_ro PASSWORD :'cost_password';
GRANT USAGE ON SCHEMA public TO speaking_cost_ro;
GRANT SELECT ON voice_cost_calls, voice_cost_exchanges TO speaking_cost_ro;
GRANT SELECT ON habit_call_daily TO speaking_cost_ro;
SQL
        break
      fi
      sleep 1
    done
    if [ "$voice_cost_tables_ready" != true ]; then
      echo "Voice cost tables did not appear; Grafana PostgreSQL access was not configured" >&2
      exit 1
    fi
  else
    echo "PostgreSQL container missing; Grafana PostgreSQL access was not configured" >&2
    exit 1
  fi
fi

umask 077
printf '%s' "$TOKEN" > "$MON/ai_token"
chmod 600 "$MON/ai_token"

cat > "$MON/prometheus.yml" <<EOF
global:
  scrape_interval: 60s
scrape_configs:
  - job_name: speaking-v2
    scrape_interval: 15s
    metrics_path: /internal/metrics/prometheus
    scheme: ${AI_SCHEME}
    bearer_token_file: /etc/prometheus/ai_token
    static_configs:
      - targets: ["${AI_TARGET}"]
  - job_name: speaking-telegram
    scrape_interval: 15s
    metrics_path: /internal/telegram-metrics/prometheus
    static_configs:
      - targets: ["127.0.0.1:${MONITORING_PORT}"]
  - job_name: blackbox-ktor
    scrape_interval: 15s
    metrics_path: /probe
    params:
      module: [http_2xx_tls]
    static_configs:
      - targets: ["https://127.0.0.1:${SERVER_PORT}/health"]
    relabel_configs:
      - source_labels: [__address__]
        target_label: __param_target
      - source_labels: [__param_target]
        target_label: instance
      - target_label: __address__
        replacement: 127.0.0.1:9115
  - job_name: blackbox-ai
    scrape_interval: 15s
    metrics_path: /probe
    params:
      module: [http_2xx]
    static_configs:
      - targets: ["${AI_URL}/health"]
    relabel_configs:
      - source_labels: [__address__]
        target_label: __param_target
      - source_labels: [__param_target]
        target_label: instance
      - target_label: __address__
        replacement: 127.0.0.1:9115
EOF
chmod 644 "$MON/prometheus.yml"
chown 65534:65534 "$MON/ai_token"

VOLUMES="
volumes:
  speaking-coach-prometheus:
  speaking-coach-loki:
  speaking-coach-alloy:
  speaking-coach-daily-report:
  speaking-coach-openrouter-credit:"

cat > "$MON/compose.yml" <<EOF
x-logging: &bounded_logging
  driver: local
  options:
    max-size: "10m"
    max-file: "3"
services:
  prometheus:
    logging: *bounded_logging
    image: prom/prometheus:v2.55.1
    container_name: speaking-coach-prometheus
    network_mode: host
    restart: unless-stopped
    command:
      - --config.file=/etc/prometheus/prometheus.yml
      - --web.listen-address=127.0.0.1:9090
      - --storage.tsdb.path=/prometheus
      - --storage.tsdb.retention.time=185d
    volumes:
      - ./prometheus.yml:/etc/prometheus/prometheus.yml:ro
      - ./ai_token:/etc/prometheus/ai_token:ro
      - speaking-coach-prometheus:/prometheus
  blackbox:
    logging: *bounded_logging
    image: prom/blackbox-exporter:v0.25.0
    container_name: speaking-coach-blackbox
    network_mode: host
    restart: unless-stopped
    command:
      - --config.file=/etc/blackbox/blackbox.yml
      - --web.listen-address=127.0.0.1:9115
    volumes:
      - ./blackbox.yml:/etc/blackbox/blackbox.yml:ro
  loki:
    logging: *bounded_logging
    image: grafana/loki:3.7.0
    container_name: speaking-coach-loki
    network_mode: host
    restart: unless-stopped
    command: -config.file=/etc/loki/loki.yml
    volumes:
      - ./loki.yml:/etc/loki/loki.yml:ro
      - speaking-coach-loki:/loki
  alloy:
    logging: *bounded_logging
    image: grafana/alloy:v1.20.1
    container_name: speaking-coach-alloy
    network_mode: host
    user: "0:0"
    restart: unless-stopped
    command: run --storage.path=/var/lib/alloy /etc/alloy/config.alloy
    environment:
      SPEAKY_ENV: ${SPEAKY_ENV}
    volumes:
      - ./alloy-local.alloy:/etc/alloy/config.alloy:ro
      - /var/run/docker.sock:/var/run/docker.sock:ro
      - speaking-coach-alloy:/var/lib/alloy
EOF

if [ -z "$GRAFANA_PASSWORD" ]; then
  echo "GRAFANA_ADMIN_PASSWORD is empty; Grafana is not published" >&2
  docker rm -f speaking-coach-grafana speaking-coach-monitoring-proxy >/dev/null 2>&1 || true
else
  INGEST_PASSWORD="$TOKEN"
  if [ -z "$INGEST_PASSWORD" ]; then
    INGEST_PASSWORD=$(openssl rand -hex 32)
    echo "AI_INTERNAL_TOKEN is empty; remote Loki ingest remains locked" >&2
  fi
  HASH=$(printf '%s' "$INGEST_PASSWORD" | openssl passwd -apr1 -stdin)
  printf 'alloy:%s\n' "$HASH" > "$MON/loki.htpasswd"
  # nginx in the proxy container runs as a non-root user and must read this hash file.
  chmod 644 "$MON/loki.htpasswd"
  cat > "$MON/nginx.conf" <<EOF
server {
  listen 8443 ssl;
  server_name _;
  ssl_certificate /certs/tls.crt;
  ssl_certificate_key /certs/tls.key;
  location = /_grafana_user {
    internal;
    proxy_pass http://127.0.0.1:3000/api/user;
    proxy_pass_request_body off;
    proxy_set_header Content-Length "";
    proxy_set_header Cookie \$http_cookie;
  }
  location /admin/monitoring {
    auth_request /_grafana_user;
    error_page 401 = @need_login;
    proxy_pass http://127.0.0.1:${MONITORING_PORT};
    proxy_set_header Host \$host;
  }
  location @need_login {
    return 302 /login;
  }
  location = /loki/api/v1/push {
    auth_basic "Loki ingest";
    auth_basic_user_file /etc/nginx/loki.htpasswd;
    client_max_body_size 10m;
    proxy_pass http://127.0.0.1:3100;
  }
  location / {
    proxy_pass http://127.0.0.1:3000;
    proxy_set_header Host \$host;
    proxy_set_header X-Forwarded-Proto https;
  }
}
EOF
  cat >> "$MON/compose.yml" <<EOF
  grafana:
    logging: *bounded_logging
    image: grafana/grafana:11.3.1
    container_name: speaking-coach-grafana
    network_mode: host
    restart: unless-stopped
    environment:
      GF_SERVER_HTTP_ADDR: 127.0.0.1
      GF_SERVER_HTTP_PORT: "3000"
      GF_AUTH_ANONYMOUS_ENABLED: "false"
      GF_USERS_ALLOW_SIGN_UP: "false"
      GF_SECURITY_ADMIN_USER: admin
      GF_SECURITY_ADMIN_PASSWORD: ${GRAFANA_PASSWORD}
      GF_SERVER_ROOT_URL: ${GRAFANA_ROOT}
      VOICE_COST_DB_PASSWORD: \${VOICE_COST_DB_PASSWORD}
      VOICE_COST_DB_NAME: \${VOICE_COST_DB_NAME}
$(if [ "$ALERTS_ENABLED" = true ]; then printf '    env_file:\n      - ./alerts.env\n'; fi)
    volumes:
      - ./grafana/provisioning:/etc/grafana/provisioning:ro
      - ./grafana/dashboards:/etc/grafana/dashboards:ro
      - speaking-coach-grafana:/var/lib/grafana
  proxy:
    logging: *bounded_logging
    image: nginx:1.27-alpine
    container_name: speaking-coach-monitoring-proxy
    network_mode: host
    restart: unless-stopped
    volumes:
      - ./nginx.conf:/etc/nginx/conf.d/default.conf:ro
      - ${APP}/tls.crt:/certs/tls.crt:ro
      - ${APP}/tls.key:/certs/tls.key:ro
      - ./loki.htpasswd:/etc/nginx/loki.htpasswd:ro
EOF
  VOLUMES="
volumes:
  speaking-coach-prometheus:
  speaking-coach-grafana:
  speaking-coach-loki:
  speaking-coach-alloy:
  speaking-coach-daily-report:
  speaking-coach-openrouter-credit:"
fi

if [ "$ALERTS_ENABLED" = true ]; then
  if [ ! -s "$CREDIT_ENV" ]; then
    echo "Daily report requires OpenRouter management key configuration" >&2
    exit 1
  fi
  cat >> "$MON/compose.yml" <<EOF
  daily-report:
    logging: *bounded_logging
    build: ./reporter
    image: speaking-coach-daily-report:local
    container_name: speaking-coach-daily-report
    network_mode: host
    restart: unless-stopped
    env_file:
      - ./alerts.env
      - ./openrouter.env
    environment:
      AI_SERVICE_BASE_URL: ${AI_URL}
      AI_INTERNAL_TOKEN_FILE: /run/secrets/ai_token
    volumes:
      - ./ai_token:/run/secrets/ai_token:ro
      - speaking-coach-daily-report:/state
EOF
fi

if [ -s "$CREDIT_ENV" ]; then
  if [ "$ALERTS_ENABLED" != true ]; then
    echo "OpenRouter credit monitor requires Telegram alert configuration" >&2
    exit 1
  fi
  cat >> "$MON/compose.yml" <<EOF
  openrouter-credit:
    logging: *bounded_logging
    image: speaking-coach-daily-report:local
    container_name: speaking-coach-openrouter-credit
    network_mode: host
    restart: unless-stopped
    command: ["python", "/app/credit_monitor.py"]
    env_file:
      - ./alerts.env
      - ./openrouter.env
    volumes:
      - speaking-coach-openrouter-credit:/state
EOF
fi

printf '%s\n' "$VOLUMES" >> "$MON/compose.yml"

cd "$MON"
docker compose -p speaking-coach-monitoring -f compose.yml up -d --build
