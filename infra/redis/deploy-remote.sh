#!/usr/bin/env bash
set -euo pipefail

NETWORK=speaking-coach
docker network create "$NETWORK" >/dev/null 2>&1 || true
docker volume create speaking-coach-redis >/dev/null 2>&1 || true

if docker inspect redis >/dev/null 2>&1; then
  driver=$(docker inspect -f '{{.HostConfig.LogConfig.Type}}' redis)
  size=$(docker inspect -f '{{index .HostConfig.LogConfig.Config "max-size"}}' redis)
  command_line=$(docker inspect -f '{{join .Config.Cmd " "}}' redis)
  if [ "$driver" = "local" ] && [ "$size" = "10m" ] && [[ "$command_line" == *"--appendonly yes"* ]]; then
    docker start redis >/dev/null
    docker network connect "$NETWORK" redis >/dev/null 2>&1 || true
    echo "redis already present; left running."
    exit 0
  fi
  # Convert the retained RDB data to AOF before recreating the container.
  docker start redis >/dev/null
  docker exec redis redis-cli CONFIG SET appendonly yes >/dev/null
  for attempt in $(seq 1 120); do
    if docker exec redis redis-cli INFO persistence | grep -q '^aof_rewrite_in_progress:0' && \
       docker exec redis redis-cli INFO persistence | grep -q '^aof_last_bgrewrite_status:ok'; then
      break
    fi
    sleep 1
  done
  if ! docker exec redis redis-cli INFO persistence | grep -q '^aof_last_bgrewrite_status:ok'; then
    echo "Redis AOF rewrite failed; keeping the existing container" >&2
    exit 1
  fi
  echo "recreating redis with AOF; data volume stays" >&2
  docker stop redis >/dev/null
  docker rm redis >/dev/null
fi

docker run -d --name redis --restart unless-stopped \
  --log-driver local --log-opt max-size=10m --log-opt max-file=3 \
  --network "$NETWORK" \
  -p 127.0.0.1:6379:6379 \
  -v speaking-coach-redis:/data \
  redis:7-alpine \
  redis-server --save 60 1 --appendonly yes --appendfsync everysec --loglevel warning
