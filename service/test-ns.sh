#!/bin/bash
# Runs the given test namespaces only: ./test-ns.sh manager-svc.sessions.core-test ...
set -euo pipefail
cd "$(dirname "$0")"
nses=""
for ns in "$@"; do nses="$nses '$ns"; done
exec clojure -M:test -e "(require 'clojure.test $nses) (let [r (clojure.test/run-tests $nses)] (shutdown-agents) (System/exit (if (zero? (+ (:fail r) (:error r))) 0 1)))"
