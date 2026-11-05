#!/usr/bin/env bash
# Publish the example definitions (and anything in local/sources/<name>) as git repositories under
# local/repos/<name>, which compose mounts into the manager as file:///repos/<name>.
set -euo pipefail
root="$(cd "$(dirname "$0")/.." && pwd)"
repos="$root/local/repos"
mkdir -p "$repos"

publish() {
  local name="$1" source="$2" target="$repos/$1"
  [ -d "$target/.git" ] || git -C "$repos" init --quiet --initial-branch=main "$name"
  find "$target" -mindepth 1 -maxdepth 1 ! -name .git -exec rm -rf {} +
  cp -a "$source/." "$target/"
  git -C "$target" add --all
  if git -C "$target" diff --cached --quiet; then
    echo "  $name: unchanged"
  else
    git -C "$target" -c user.name=platform -c user.email=platform@localhost commit --quiet -m "Sync from $(basename "$source")"
    echo "  $name: $(git -C "$target" rev-parse --short HEAD)"
  fi
}

echo "Publishing repositories to $repos"
publish onboarding "$root/examples/onboarding"
publish demo-team "$root/examples/team-repo"
publish demo-service "$root/examples/demo-service"
if [ -d "$root/local/sources" ]; then
  for source in "$root"/local/sources/*/; do
    publish "$(basename "$source")" "$source"
  done
fi
