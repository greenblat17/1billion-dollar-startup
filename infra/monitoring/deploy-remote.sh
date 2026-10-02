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

umask 077
printf '%s' "$TOKEN" > "$MON/ai_token"
chmod 600 "$MON/ai_token"

cat > "$MON/prometheus.yml" <<EOF
global:
  scrape_interval: 60s
scrape_configs:
  - job_name: speaking-v2
    scrape_interval: 60s
    metrics_path: /internal/metrics/prometheus
    scheme: ${AI_SCHEME}
    bearer_token_file: /etc/prometheus/ai_token
    static_configs:
      - targets: ["${AI_TARGET}"]
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
  speaking-coach-prometheus:"

cat > "$MON/compose.yml" <<EOF
services:
  prometheus:
    image: prom/prometheus:v2.55.1
    container_name: speaking-coach-prometheus
    network_mode: host
    restart: unless-stopped
    command:
      - --config.file=/etc/prometheus/prometheus.yml
      - --web.listen-address=127.0.0.1:9090
      - --storage.tsdb.path=/prometheus
    volumes:
      - ./prometheus.yml:/etc/prometheus/prometheus.yml:ro
      - ./ai_token:/etc/prometheus/ai_token:ro
      - speaking-coach-prometheus:/prometheus
  blackbox:
    image: prom/blackbox-exporter:v0.25.0
    container_name: speaking-coach-blackbox
    network_mode: host
    restart: unless-stopped
    command:
      - --config.file=/etc/blackbox/blackbox.yml
      - --web.listen-address=127.0.0.1:9115
    volumes:
      - ./blackbox.yml:/etc/blackbox/blackbox.yml:ro
EOF

if [ -z "$GRAFANA_PASSWORD" ]; then
  echo "GRAFANA_ADMIN_PASSWORD is empty; Grafana is not published" >&2
  docker rm -f speaking-coach-grafana speaking-coach-monitoring-proxy >/dev/null 2>&1 || true
else
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
  location / {
    proxy_pass http://127.0.0.1:3000;
    proxy_set_header Host \$host;
    proxy_set_header X-Forwarded-Proto https;
  }
}
EOF
  cat >> "$MON/compose.yml" <<EOF
  grafana:
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
    volumes:
      - ./grafana/provisioning:/etc/grafana/provisioning:ro
      - ./grafana/dashboards:/etc/grafana/dashboards:ro
      - speaking-coach-grafana:/var/lib/grafana
  proxy:
    image: nginx:1.27-alpine
    container_name: speaking-coach-monitoring-proxy
    network_mode: host
    restart: unless-stopped
    volumes:
      - ./nginx.conf:/etc/nginx/conf.d/default.conf:ro
      - ${APP}/tls.crt:/certs/tls.crt:ro
      - ${APP}/tls.key:/certs/tls.key:ro
EOF
  VOLUMES="
volumes:
  speaking-coach-prometheus:
  speaking-coach-grafana:"
fi

printf '%s\n' "$VOLUMES" >> "$MON/compose.yml"

cd "$MON"
docker compose -p speaking-coach-monitoring -f compose.yml up -d
