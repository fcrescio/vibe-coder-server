#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
DOCKER_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"

COMPOSE_FILE="${COMPOSE_FILE:-$DOCKER_DIR/compose.yml}"
SERVER_CONTAINER="${SERVER_CONTAINER:-vibe-coder-server}"
SERVICE="${SERVICE:-vibe-coder-server}"

if [[ -z "${VIBECODER_DB_PASSWORD:-}" ]]; then
  if ! docker inspect "$SERVER_CONTAINER" >/dev/null 2>&1; then
    echo "error: VIBECODER_DB_PASSWORD is not set and container '$SERVER_CONTAINER' was not found" >&2
    exit 1
  fi
  VIBECODER_DB_PASSWORD="$(
    docker exec "$SERVER_CONTAINER" sh -lc 'printf "%s" "$VIBECODER_DB_PASSWORD"'
  )"
fi

if [[ -z "$VIBECODER_DB_PASSWORD" ]]; then
  echo "error: VIBECODER_DB_PASSWORD is empty" >&2
  exit 1
fi

export VIBECODER_DB_PASSWORD

cd "$DOCKER_DIR"
docker compose -f "$COMPOSE_FILE" build "$SERVICE"
docker compose -f "$COMPOSE_FILE" up -d "$SERVICE"
