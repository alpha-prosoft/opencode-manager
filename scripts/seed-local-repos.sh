#!/usr/bin/env bash
# Publish the example tenants as git repositories under local/repos/<name>, which compose mounts into the
# manager as file:///repos/<name>: examples/tenants/<tenant>/onboarding as <tenant>-onboarding, every team repo
# examples/tenants/<tenant>/teams/<team> as <tenant>-<team>, the demo service, and anything in local/sources/<name>.
#
# SEED_VARIANT=docs publishes the documentation variant instead (make docs-stack): every LLM connection points at
# the scripted LLM of compose.docs.yml, scheduled workflows run less often, and each repository is made anew as one
# commit with a fixed date, so its version is the same on every run. The examples themselves are never changed;
# local/sources is left out.
set -euo pipefail
root="$(cd "$(dirname "$0")/.." && pwd)"
repos="$root/local/repos"
variant="${SEED_VARIANT:-}"
mkdir -p "$repos"

if [ "$variant" = docs ]; then
  export GIT_AUTHOR_DATE="2025-01-01T00:00:00Z" GIT_COMMITTER_DATE="2025-01-01T00:00:00Z"
elif [ -n "$variant" ]; then
  echo "Unknown SEED_VARIANT '$variant' (docs, or leave it out)" >&2
  exit 1
fi

# What the docs variant changes in a published repository: every LLM connection of a tenant.json becomes the
# scripted LLM (same id, models, prices and secret name), and scheduled workflows run every DOCS_SCHEDULE_MINUTES (default 8),
# so that exactly one scheduled run falls into a documentation cycle, at a known point.
docs_variant() {
  DOCS_SCHEDULE_MINUTES="${DOCS_SCHEDULE_MINUTES:-8}" python3 - "$1" <<'PYTHON'
import json
import os
import pathlib
import sys

root = pathlib.Path(sys.argv[1])


def rewrite(path, change):
    document = json.loads(path.read_text(encoding="utf-8"))
    if change(document):
        path.write_text(json.dumps(document, indent=2) + "\n", encoding="utf-8")


def scripted_llm(tenant):
    for connection in tenant.get("llm_connections", []):
        connection.update({"type": "openai-compatible", "base_url": "http://llm-script:8000/v1"})
    return True


def schedule(workflow):
    trigger = workflow.get("trigger") or {}
    if trigger.get("type") != "schedule":
        return False
    trigger["every_minutes"] = int(os.environ["DOCS_SCHEDULE_MINUTES"])
    return True


if (root / "tenant.json").is_file():
    rewrite(root / "tenant.json", scripted_llm)
for workflow in sorted(root.glob(".platform/workflows/*.json")):
    rewrite(workflow, schedule)
PYTHON
}

publish() {
  local name="$1" source="$2" target="$repos/$1"
  [ "$variant" = docs ] && rm -rf "$target"
  [ -d "$target/.git" ] || git -C "$repos" init --quiet --initial-branch=main "$name"
  find "$target" -mindepth 1 -maxdepth 1 ! -name .git -exec rm -rf {} +
  cp -a "$source/." "$target/"
  [ "$variant" != docs ] || docs_variant "$target"
  git -C "$target" add --all
  if git -C "$target" diff --cached --quiet; then
    echo "  $name: unchanged"
  else
    git -C "$target" -c user.name=platform -c user.email=platform@localhost commit --quiet -m "Sync from $(basename "$source")"
    echo "  $name: $(git -C "$target" rev-parse --short HEAD)"
  fi
}

# Branch <branch> of repository <name>: main with the files of <fixture> on top.
fixture_branch() {
  local name="$1" branch="$2" fixture="$3" target="$repos/$1"
  git -C "$target" checkout --quiet -B "$branch" main
  cp -a "$fixture/." "$target/"
  git -C "$target" add --all
  git -C "$target" -c user.name=platform -c user.email=platform@localhost commit --quiet -m "Apply $(basename "$fixture")"
  git -C "$target" checkout --quiet main
  echo "  $name@$branch: $(git -C "$target" rev-parse --short "$branch")"
}

echo "Publishing repositories to $repos${variant:+ ($variant variant)}"
for tenant_dir in "$root"/examples/tenants/*/; do
  tenant="$(basename "$tenant_dir")"
  publish "$tenant-onboarding" "$tenant_dir/onboarding"
  for team_dir in "$tenant_dir"/teams/*/; do
    publish "$tenant-$(basename "$team_dir")" "$team_dir"
  done
done
publish demo-service "$root/examples/demo-service"
fixture_branch demo-service e2e-review "$root/examples/review-fixture"
if [ "$variant" != docs ] && [ -d "$root/local/sources" ]; then
  for source in "$root"/local/sources/*/; do
    publish "$(basename "$source")" "$source"
  done
fi
echo "The local Gerrit (docker compose -f compose.yml -f compose.ci.yml up -d) imports examples/demo-service itself on its first start."
