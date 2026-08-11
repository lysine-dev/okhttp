#!/usr/bin/env bash

set -euo pipefail

mode="${1:-instrumentation}"
if [[ "$mode" != "instrumentation" && "$mode" != "--smoke-only" ]]; then
  echo "usage: $0 [--smoke-only]" >&2
  exit 2
fi

temporary_dir="${RUNNER_TEMP:-${TMPDIR:-/tmp}}"
endpoint_file="$temporary_dir/okhttp-android-ech-test.endpoint"
service_log="$temporary_dir/okhttp-android-ech-test.log"
startup_timeout_seconds="${ANDROID_ECH_TEST_SERVICE_TIMEOUT_SECONDS:-1200}"
rm -f "$endpoint_file" "$service_log"

ANDROID_ECH_TEST_ENDPOINT_FILE="$endpoint_file" \
  ./gradlew :container-tests:runAndroidEchTestService >"$service_log" 2>&1 &
service_pid=$!

cleanup() {
  adb reverse --remove tcp:8053 >/dev/null 2>&1 || true
  adb reverse --remove tcp:443 >/dev/null 2>&1 || true
  adb reverse --remove tcp:8443 >/dev/null 2>&1 || true
  rm -f "$endpoint_file"
  for _ in {1..200}; do
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
  echo "Timed out waiting for the ECH test services" >&2
  exit 1
fi

property() {
  sed -n "s/^$1=//p" "$endpoint_file"
}

doh_host_port="$(property DOH_HOST_PORT)"
target_host_port="$(property TARGET_HOST_PORT)"
ca_certificate="$(property CA_CERT)"
if [[ ! "$doh_host_port" =~ ^[0-9]+$ || ! "$target_host_port" =~ ^[0-9]+$ || -z "$ca_certificate" ]]; then
  cat "$service_log" >&2
  echo "Invalid ECH test service metadata" >&2
  exit 1
fi

if [[ "$mode" == "--smoke-only" ]]; then
  exit 0
fi

adb reverse tcp:8053 "tcp:$doh_host_port"
adb reverse tcp:443 "tcp:$target_host_port"
adb reverse tcp:8443 "tcp:$target_host_port"
./gradlew :android-test:connectedDebugAndroidTest \
  -PandroidBuild=true \
  -Pandroid.testInstrumentationRunnerArguments.class=okhttp.android.test.EncryptedClientHelloTest \
  -Pandroid.testInstrumentationRunnerArguments.ech=true \
  -Pandroid.testInstrumentationRunnerArguments.dohPort=8053 \
  -Pandroid.testInstrumentationRunnerArguments.caCertificate="$ca_certificate"
