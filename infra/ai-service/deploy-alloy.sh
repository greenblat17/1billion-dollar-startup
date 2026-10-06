#!/usr/bin/env bash
# Ship AI-host Docker logs to the authenticated Loki endpoint on the CMP host.
set -euo pipefail

APP=/opt/ai-service
MON="$APP/monitoring"
ENV_FILE="$APP/.env"

env_get() {
  local key="$1"
  local line
  line=$(grep -E "^${key}=" "$ENV_FILE" | tail -n 1 || true)
  printf '%s' "${line#"${key}"=}"
}

LOKI_URL=$(env_get LOKI_PUSH_URL)
TOKEN=$(env_get AI_INTERNAL_TOKEN)
SPEAKY_ENV=$(env_get SPEAKY_ENV)
SPEAKY_ENV=${SPEAKY_ENV:-unknown}
if [ -z "$LOKI_URL" ] || [ -z "$TOKEN" ]; then
  echo "LOKI_PUSH_URL or AI_INTERNAL_TOKEN missing; AI log shipping skipped" >&2
  exit 0
fi
case "$LOKI_URL" in
  https://*/loki/api/v1/push) ;;
  *) echo "LOKI_PUSH_URL must be an HTTPS Loki push endpoint" >&2; exit 1 ;;
esac

mkdir -p "$MON"
umask 077
printf '%s' "$TOKEN" > "$MON/ingest_password"

cat > "$MON/config.alloy" <<'EOF'
discovery.docker "containers" {
  host = "unix:///var/run/docker.sock"
}

discovery.relabel "services" {
  targets = []
  rule {
    source_labels = ["__meta_docker_container_name"]
    regex = "/(.*)"
    target_label = "service_name"
  }
}

loki.source.docker "containers" {
  host = "unix:///var/run/docker.sock"
  targets = discovery.docker.containers.targets
  relabel_rules = discovery.relabel.services.rules
  labels = { host = "ai", environment = sys.env("SPEAKY_ENV") }
  forward_to = [loki.write.remote.receiver]
}

loki.write "remote" {
  endpoint {
    url = sys.env("LOKI_PUSH_URL")
    basic_auth {
      username = "alloy"
      password_file = "/etc/alloy/ingest_password"
    }
EOF
if [ -f "$MON/loki-ca.crt" ]; then
  cat >> "$MON/config.alloy" <<'EOF'
    tls_config {
      ca_file = "/etc/alloy/loki-ca.crt"
    }
EOF
fi
cat >> "$MON/config.alloy" <<'EOF'
  }
}
EOF

docker volume create ai-service-alloy >/dev/null
docker rm -f ai-service-alloy >/dev/null 2>&1 || true
ARGS=(
  -d --name ai-service-alloy --restart unless-stopped
  --log-driver local --log-opt max-size=10m --log-opt max-file=3
  --network host --user 0:0
  -e "LOKI_PUSH_URL=$LOKI_URL"
  -e "SPEAKY_ENV=$SPEAKY_ENV"
  -v /var/run/docker.sock:/var/run/docker.sock:ro
  -v "$MON/config.alloy:/etc/alloy/config.alloy:ro"
  -v "$MON/ingest_password:/etc/alloy/ingest_password:ro"
  -v ai-service-alloy:/var/lib/alloy
)
if [ -f "$MON/loki-ca.crt" ]; then
  ARGS+=(-v "$MON/loki-ca.crt:/etc/alloy/loki-ca.crt:ro")
fi
docker run "${ARGS[@]}" grafana/alloy:v1.20.1 \
  run --storage.path=/var/lib/alloy /etc/alloy/config.alloy >/dev/null
