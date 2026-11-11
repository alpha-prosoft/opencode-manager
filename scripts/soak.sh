#!/usr/bin/env bash
# Runs the API end-to-end suite and the Playwright suite against a running
# stack several times, one after the other, and records for every run how long
# it took, what it reported, what the manager logged meanwhile and whether a
# failure went away on a retry.
#
#   scripts/soak.sh [iterations] [pause-seconds]
#
# MANAGER_URL (default http://localhost:8080), SOAK_OUT (default a new
# directory under /tmp) and SOAK_MANAGER (the manager's container, default
# opencode-manager-manager-1) may be set. Results: $SOAK_OUT/summary.tsv, one
# log per run, errors-<n>.log per iteration, threads-<n>-<suite>.log (the
# manager's thread dump, taken when a suite fails) and report.txt at the end.
set -u

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
ITERATIONS="${1:-4}"
PAUSE="${2:-1800}"
OUT="${SOAK_OUT:-/tmp/manager-soak-$(date +%Y%m%d-%H%M%S)}"
MANAGER="${SOAK_MANAGER:-opencode-manager-manager-1}"
export MANAGER_URL="${MANAGER_URL:-http://localhost:8080}"

mkdir -p "$OUT"
SUMMARY="$OUT/summary.tsv"
[ -f "$SUMMARY" ] || printf 'time\titeration\tsuite\tattempt\texit\tseconds\tresult\n' > "$SUMMARY"

result_line() {
  grep -E '[0-9]+/[0-9]+ checks passed|[0-9]+ tests, [0-9]+ assertions' "$1" | tail -1 | sed 's/\x1b\[[0-9;]*m//g'
}

record() {
  printf '%s\t%s\t%s\t%s\t%s\t%s\t%s\n' "$(date -Is)" "$1" "$2" "$3" "$4" "$5" "$(result_line "$6")" >> "$SUMMARY"
}

run_suite() {
  local iteration="$1" suite="$2" attempt="$3" log="$4"
  shift 4
  local started
  started=$(date +%s)
  "$@" > "$log" 2>&1
  local code=$?
  record "$iteration" "$suite" "$attempt" "$code" "$(( $(date +%s) - started ))" "$log"
  return $code
}

e2e() {
  (cd "$ROOT/service" && timeout 3h clojure -M:e2e)
}

it() {
  (cd "$ROOT/web" && timeout 3h clojure -M:it "$@")
}

failed_namespaces() {
  sed 's/\x1b\[[0-9;]*m//g' "$1" | grep -oE '(FAIL|ERROR) in [a-z0-9.-]+-it' | awk '{print $3}' | sort -u
}

thread_dump() {
  local since
  since=$(date -Is)
  docker kill --signal QUIT "$MANAGER" > /dev/null 2>&1 || return 0
  sleep 2
  docker logs --since "$since" "$MANAGER" > "$OUT/threads-$1-$2.log" 2>&1
}

service_errors() {
  local iteration="$1" since="$2" file="$OUT/errors-$1.log"
  docker logs --since "$since" "$MANAGER" 2>&1 \
    | grep -E 'Exception|ERROR' \
    | grep -vE ':status 4[0-9][0-9]|^\s+at |\[\{:type |^\s*VALUES |^\s*Detail: ' \
    | sed -E 's/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}/<id>/g; s/^[0-9T:.,Z -]+//' \
    | cut -c1-200 | sort | uniq -c | sort -rn > "$file"
  local restarts
  restarts=$(docker ps -a --filter name=opencode-manager --format '{{.Names}}' \
    | xargs -r docker inspect --format '{{.Name}} restarts={{.RestartCount}} oom={{.State.OOMKilled}} status={{.State.Status}} exit={{.State.ExitCode}}' \
    | grep -vE 'restarts=0 oom=false status=(running|exited) exit=0' || true)
  printf '%s\t%s\t%s\t%s\t%s\t%s\t%s\n' "$(date -Is)" "$iteration" "service-log" "-" "-" "-" \
    "$(awk '{n+=$1} END {print n+0}' "$file") unexpected error lines, $(wc -l < "$file") kinds${restarts:+; containers: $restarts}" >> "$SUMMARY"
}

report() {
  {
    echo "Soak of $MANAGER_URL, $ITERATIONS iterations"
    echo
    echo "Duration in seconds (first attempts):"
    awk -F'\t' '$3 ~ /^(e2e|it)$/ && $4 == 1 {n[$3]++; s[$3]+=$6; if (min[$3]=="" || $6<min[$3]) min[$3]=$6; if ($6>max[$3]) max[$3]=$6}
                END {for (k in n) printf "  %-4s runs=%d min=%d avg=%d max=%d\n", k, n[k], min[k], s[k]/n[k], max[k]}' "$SUMMARY"
    echo
    echo "Failed first attempts (exit, result) and their retries:"
    awk -F'\t' '$3 ~ /^(e2e|it)$/ && ($5 != 0 || $4 > 1) {printf "  iteration %s %s attempt %s exit %s: %s\n", $2, $3, $4, $5, $7}' "$SUMMARY"
    echo
    echo "Manager log per iteration:"
    awk -F'\t' '$3 == "service-log" {printf "  iteration %s: %s\n", $2, $7}' "$SUMMARY"
    echo
    echo "Slowest e2e sections and Playwright tests of iteration $1:"
    grep -A15 '^Slowest sections' "$OUT/e2e-$1-1.log" 2>/dev/null
    sed 's/\x1b\[[0-9;]*m//g' "$OUT/it-$1-1.log" 2>/dev/null | grep -A12 'Slowest vars'
  } > "$OUT/report.txt"
}

for iteration in $(seq 1 "$ITERATIONS"); do
  since=$(date -Is)

  if ! run_suite "$iteration" e2e 1 "$OUT/e2e-$iteration-1.log" e2e; then
    thread_dump "$iteration" e2e
    run_suite "$iteration" e2e 2 "$OUT/e2e-$iteration-2.log" e2e
  fi

  if ! run_suite "$iteration" it 1 "$OUT/it-$iteration-1.log" it; then
    thread_dump "$iteration" it
    focus=()
    for namespace in $(failed_namespaces "$OUT/it-$iteration-1.log"); do
      focus+=(--focus "$namespace")
    done
    run_suite "$iteration" it 2 "$OUT/it-$iteration-2.log" it "${focus[@]}"
  fi

  service_errors "$iteration" "$since"
  report "$iteration"
  [ "$iteration" -lt "$ITERATIONS" ] && sleep "$PAUSE"
done

echo done >> "$OUT/report.txt"
