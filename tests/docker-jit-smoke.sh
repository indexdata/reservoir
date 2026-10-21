#!/usr/bin/env bash
set -euo pipefail

# With no argument, build the image. CI can pass an image it has already built.
repo_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)
export RESERVOIR_TEST_IMAGE=${1:-reservoir-jit:smoke-test}
project="reservoir-jit-smoke-$$"
compose=(docker compose -p "$project" -f "$repo_dir/tests/docker-compose-jit.yml")

cleanup() {
  result=$?
  trap - EXIT
  if (( result != 0 )); then
    "${compose[@]}" ps -a || true
    "${compose[@]}" logs --no-color || true
  fi
  "${compose[@]}" down --volumes --remove-orphans || true
  exit "$result"
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

if (( $# == 0 )); then
  docker build -f "$repo_dir/Dockerfile-jit" -t "$RESERVOIR_TEST_IMAGE" "$repo_dir"
fi

"${compose[@]}" up -d --wait --wait-timeout 90 postgres
"${compose[@]}" up -d reservoir
container=$("${compose[@]}" ps -q reservoir)
address=$("${compose[@]}" port reservoir 8081)

# Require repeated successful requests so a process that exits just after binding
# its HTTP port does not pass. /healthz also verifies database connectivity.
successes=0
deadline=$((SECONDS + 120))
while (( SECONDS < deadline )); do
  if [[ $(docker inspect -f '{{.State.Running}}' "$container") != true ]]; then
    echo "Reservoir exited during startup" >&2
    exit 1
  fi
  if body=$(curl --fail --silent --show-error --connect-timeout 1 --max-time 3 \
      "http://$address/healthz" 2>/dev/null) && [[ $body == OK ]]; then
    successes=$((successes + 1))
    if (( successes >= 5 )); then
      echo "Dockerfile-jit smoke test passed: /healthz returned OK"
      exit 0
    fi
  else
    successes=0
  fi
  sleep 1
done

echo "Timed out waiting for Reservoir /healthz to return OK" >&2
exit 1
