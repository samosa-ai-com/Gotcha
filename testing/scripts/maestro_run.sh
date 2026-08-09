#!/usr/bin/env bash
# Wrapper around `maestro test` that does the setup Maestro itself cannot do:
# start the mock LLM server, reverse its port to the device, install the debug
# APK, grant the overlay appop, and seed settings via the debug broadcast hook.
#
# Usage: testing/scripts/maestro_run.sh [maestro test args...]
# Defaults to running every flow in .maestro/flows.
set -euo pipefail

ANDROID_HOME="${ANDROID_HOME:-${LOCALAPPDATA:-}/Android/Sdk}"
ADB="$ANDROID_HOME/platform-tools/adb"

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
DEBUG_APK="$REPO_ROOT/app/build/outputs/apk/debug/app-debug.apk"
PACKAGE_NAME="com.gotcha"
MOCK_PORT=8080

if [[ ! -f "$DEBUG_APK" ]]; then
    echo "Debug APK not found at $DEBUG_APK — run ./gradlew :app:assembleDebug first." >&2
    exit 1
fi

echo "Starting mock LLM server on :$MOCK_PORT…"
python3 "$REPO_ROOT/testing/scripts/mock_llm_server.py" "$MOCK_PORT" &
MOCK_SERVER_PID=$!
trap 'kill "$MOCK_SERVER_PID" 2>/dev/null || true' EXIT

sleep 1

echo "Reversing device port $MOCK_PORT to host…"
"$ADB" reverse "tcp:$MOCK_PORT" "tcp:$MOCK_PORT"

echo "Installing debug APK…"
"$ADB" install -g "$DEBUG_APK"

echo "Granting overlay appop…"
"$ADB" shell appops set "$PACKAGE_NAME" SYSTEM_ALERT_WINDOW allow

echo "Seeding settings…"
"$ADB" shell am broadcast -n "$PACKAGE_NAME/com.gotcha.debug.TestHooksReceiver" \
    -a com.gotcha.debug.SEED_SETTINGS \
    --es base_url "http://localhost:$MOCK_PORT/v1/" --es api_key test --es model test-model

echo "Running Maestro flows…"
if [[ $# -gt 0 ]]; then
    maestro test "$@"
else
    maestro test "$REPO_ROOT/.maestro/flows"
fi
