#!/usr/bin/env bash
set -euo pipefail

repo_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)
binary="$repo_dir/server/target/reservoir-native"
port=${RESERVOIR_SMOKE_PORT:-19281}
work_dir=$(mktemp -d)
server_pid=

cleanup() {
  result=$?
  trap - EXIT
  if [[ -n $server_pid ]]; then
    kill "$server_pid" 2>/dev/null || true
    wait "$server_pid" 2>/dev/null || true
  fi
  if (( result != 0 )); then
    cat "$work_dir/server.log" 2>/dev/null || true
  fi
  rm -rf -- "$work_dir"
  exit "$result"
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

# Run outside the repository so loose files cannot hide missing embedded resources.
cd "$work_dir"
timeout --kill-after=5s 150s "$binary" "-Dhttp.port=$port" > server.log 2>&1 &
server_pid=$!

# Startup loads OpenAPI and checks the JavaScript engine. /healthz checks PostgreSQL.
# Require repeated successes to catch a process exiting just after binding its port.
successes=0
deadline=$((SECONDS + 120))
while (( SECONDS < deadline )); do
  if ! kill -0 "$server_pid" 2>/dev/null; then
    echo "Native executable exited during startup" >&2
    exit 1
  fi
  if body=$(curl --fail --silent --show-error --connect-timeout 1 --max-time 3 \
      "http://127.0.0.1:$port/healthz" 2>/dev/null) && [[ $body == OK ]]; then
    successes=$((successes + 1))
    if (( successes >= 5 )); then
      echo "Native smoke test passed: /healthz returned OK"
      exit 0
    fi
  else
    successes=0
  fi
  sleep 1
done

echo "Timed out waiting for native executable /healthz to return OK" >&2
exit 1
