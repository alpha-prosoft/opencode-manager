#!/usr/bin/env bash
# Wait until the local stack answers: the manager through Traefik, Keycloak, Gerrit (set up by gerrit-init) and
# Jenkins, and the first sync of every tenant has finished. Usage: scripts/wait-for-stack.sh [seconds, default 600]
set -euo pipefail
root="$(cd "$(dirname "$0")/.." && pwd)"
deadline=$(( $(date +%s) + ${1:-600} ))
manager="${MANAGER_URL:-http://localhost:8080}"
keycloak="http://keycloak.localhost:${KEYCLOAK_PORT:-8180}/realms/agent-platform/.well-known/openid-configuration"
tenants="$(find "$root/examples/tenants" -mindepth 1 -maxdepth 1 -type d | wc -l)"

answers() {
  curl --silent --fail --output /dev/null --max-time 5 "$@"
}

synced() {
  local container
  container="$(docker ps --quiet --filter "label=com.docker.compose.service=manager" | head -1)"
  [ -n "$container" ] &&
    [ "$(docker logs "$container" 2>&1 | grep -c 'command/record-tenant-sync.*status: 200')" -ge "$tenants" ]
}

wait_for() {
  local what="$1"
  shift
  until "$@"; do
    if [ "$(date +%s)" -ge "$deadline" ]; then
      echo "Timed out waiting for $what" >&2
      exit 1
    fi
    sleep 3
  done
  echo "  $what: ready"
}

echo "Waiting for the stack at $manager"
wait_for "manager" answers "$manager/healthz"
wait_for "Keycloak" answers "$keycloak"
wait_for "Gerrit" answers -H "Host: gerrit.localhost" "$manager/config/server/version"
wait_for "Jenkins" answers -H "Host: jenkins.localhost" "$manager/login"
wait_for "first sync of $tenants tenants" synced
