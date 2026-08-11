#!/usr/bin/env bash

set -euo pipefail

mode="${1:-instrumentation}"
if [[ "$mode" != "instrumentation" && "$mode" != "--smoke-only" ]]; then
  echo "usage: $0 [--smoke-only]" >&2
  exit 2
fi

temporary_dir="${RUNNER_TEMP:-${TMPDIR:-/tmp}}"
endpoint_file="$temporary_dir/okhttp-android-test-service.endpoint"
service_log="$temporary_dir/okhttp-android-test-service.log"
startup_timeout_seconds="${ANDROID_TEST_SERVICE_TIMEOUT_SECONDS:-600}"
rm -f "$endpoint_file" "$service_log"

ANDROID_TEST_SERVICE_ENDPOINT_FILE="$endpoint_file" \
  ./gradlew :container-tests:runAndroidTestService >"$service_log" 2>&1 &
service_pid=$!

cleanup() {
  adb reverse --remove tcp:8080 >/dev/null 2>&1 || true
  # Removing the endpoint file asks the launcher to stop its container and exit cleanly.
  rm -f "$endpoint_file"
  for _ in {1..100}; do
    if ! kill -0 "$service_pid" 2>/dev/null; then
      break
    fi
    sleep 0.1
  done
  kill "$service_pid" >/dev/null 2>&1 || true
  wait "$service_pid" >/dev/null 2>&1 || true
}
trap cleanup EXIT

startup_deadline=$((SECONDS + startup_timeout_seconds))
while ((SECONDS < startup_deadline)); do
  if [[ -s "$endpoint_file" ]]; then
    break
  fi
  if ! kill -0 "$service_pid" 2>/dev/null; then
    cat "$service_log" >&2
    exit 1
  fi
  sleep 1
done

if [[ ! -s "$endpoint_file" ]]; then
  cat "$service_log" >&2
  echo "Timed out waiting for the Testcontainers service" >&2
  exit 1
fi

endpoint="$(<"$endpoint_file")"
service_reachable=false
for _ in {1..120}; do
  if curl --fail --silent "$endpoint/android-test"; then
    service_reachable=true
    break
  fi
  sleep 0.25
done
if [[ "$service_reachable" != "true" ]]; then
  cat "$service_log" >&2
  echo "Testcontainers service is not reachable at $endpoint" >&2
  exit 1
fi
echo

if [[ "$mode" == "--smoke-only" ]]; then
  exit 0
fi

service_port="${endpoint##*:}"
if [[ ! "$service_port" =~ ^[0-9]+$ ]]; then
  echo "Could not read the mapped port from $endpoint" >&2
  exit 1
fi

adb reverse tcp:8080 "tcp:$service_port"
./gradlew :android-test:connectedDebugAndroidTest \
  -PandroidBuild=true \
  -Pandroid.testInstrumentationRunnerArguments.class=okhttp.android.test.TestcontainersHostTest \
  -Pandroid.testInstrumentationRunnerArguments.testcontainers=true
