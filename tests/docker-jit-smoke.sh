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
"${compose[@]}" run --rm --no-deps probe
"${compose[@]}" up -d reservoir
container=$("${compose[@]}" ps -q reservoir)
runtime_modules=$(docker exec "$container" java --list-modules)
if [[ $runtime_modules == *java.desktop@* ]]; then
  echo "The JIT runtime unexpectedly includes java.desktop" >&2
  exit 1
fi
if [[ $runtime_modules == *java.security.sasl@* ]]; then
  echo "The JIT runtime unexpectedly includes java.security.sasl" >&2
  exit 1
fi
# A second JVM inherits the same options as the server, including selective overrides.
runtime_flags=$(docker exec "$container" java -XX:+PrintFlagsFinal -version)
if ! grep -Eq 'MaxRAMPercentage[[:space:]]*=[[:space:]]*55\.0' <<< "$runtime_flags" || \
   ! grep -Eq 'UseCompactObjectHeaders[[:space:]]*=[[:space:]]*false' <<< "$runtime_flags"; then
  echo "JDK_JAVA_OPTIONS did not override JAVA_TOOL_OPTIONS defaults" >&2
  exit 1
fi
address=$("${compose[@]}" port reservoir 18081)

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
      break
    fi
  else
    successes=0
  fi
  sleep 1
done

if (( successes < 5 )); then
  echo "Timed out waiting for Reservoir /healthz to return OK" >&2
  exit 1
fi

# Exercise MARC-8 conversion and persistence in the packaged image.
http=(curl --fail --silent --show-error --connect-timeout 3 --max-time 40
  -H 'X-Okapi-Tenant:smoke')
tenant_job=$("${http[@]}" -H 'Content-Type:application/json' \
  --data '{"module_to":"mod-reservoir-1.0.0"}' "http://$address/_/tenant" | jq -er '.id')
"${http[@]}" "http://$address/_/tenant/$tenant_job?wait=30000" | \
  jq -e '.complete == true and (.error // "") == ""'

"${http[@]}" -H 'Content-Type:application/octet-stream' \
  -H 'X-Okapi-Permissions:["reservoir-upload.all-sources"]' \
  --upload-file "$repo_dir/server/src/test/resources/marc3.marc" \
  "http://$address/reservoir/upload?sourceId=SMOKE&fileName=marc3.marc" | \
  jq -e '."marc3.marc" | .processed == 3 and .inserted == 3
    and .ignored == 0 and .updated == 0 and .deleted == 0'

# The fixture's third record contains MARC-8 characters that must become Unicode.
"${http[@]}" --get --data-urlencode 'query=sourceId=SMOKE' --data-urlencode 'count=exact' \
  "http://$address/reservoir/records" | \
  jq -e '.resultInfo.totalRecords == 3 and (.items | length) == 3
    and any(.items[].payload.marc | .. | strings; contains("Copyright mark (©)"))'

echo "Dockerfile-jit smoke test passed: health, tenant setup and MARC-8 ingest/read-back"
