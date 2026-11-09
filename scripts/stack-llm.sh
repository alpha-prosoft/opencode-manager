#!/usr/bin/env bash
# Say which LLM the running local stack uses: the scripted one of the documentation stack (make docs-stack), or
# the connections of the published example tenants. Warns when the guides would be made with a real model.
set -euo pipefail
root="$(cd "$(dirname "$0")/.." && pwd)"
tenant_file="$root/local/repos/acme-onboarding/tenant.json"
scripted_container="$(docker ps --quiet --filter "label=com.docker.compose.service=llm-script" | head -1)"

connections() {
  python3 - "$tenant_file" <<'PYTHON'
import json
import sys

with open(sys.argv[1], encoding="utf-8") as file:
    for connection in json.load(file).get("llm_connections", []):
        print(f"{connection['id']} ({connection.get('type', 'openai-compatible')}, {connection.get('base_url', 'provider default')})")
PYTHON
}

if [ -n "$scripted_container" ] && grep -q '"http://llm-script:' "$tenant_file" 2>/dev/null; then
  echo "LLM: scripted (llm-script, images/llm-script/scenarios.json). Screenshots are reproducible."
else
  echo "LLM: $(connections 2>/dev/null | paste -sd, - || echo unknown)"
  echo "WARNING: this is not the scripted LLM. The guides will spend tokens and their content will differ from" >&2
  echo "         run to run. For reproducible screenshots run 'make docs-stack' first (or 'make docs-all')." >&2
fi
