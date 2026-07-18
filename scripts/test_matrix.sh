#!/usr/bin/env bash
# Serial multi-AVD instrumented test runner. Boots each AVD headless, installs the
# debug APK, seeds settings via the debug test-hook broadcast, runs
# connectedDebugAndroidTest and/or Maestro against it, then tears the AVD down
# before moving to the next one. This is the project's only API 26 (minSdk)
# coverage path — Gradle Managed Devices (Phase 3) supports API 27+ only.
#
# Usage:
#   scripts/test_matrix.sh [--connected] [--maestro] [avd-name ...]
#
# With no AVD names, every AVD returned by `emulator -list-avds` is run.
set -uo pipefail

ANDROID_HOME="${ANDROID_HOME:-${LOCALAPPDATA:-}/Android/Sdk}"
EMULATOR="$ANDROID_HOME/emulator/emulator"
ADB="$ANDROID_HOME/platform-tools/adb"

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
RESULTS_ROOT="$REPO_ROOT/build/matrix-results"
DEBUG_APK="$REPO_ROOT/app/build/outputs/apk/debug/app-debug.apk"

BOOT_TIMEOUT_S=300
PACKAGE_NAME="com.gotcha"

run_connected=false
run_maestro=false
avd_names=()

for arg in "$@"; do
    case "$arg" in
        --connected) run_connected=true ;;
        --maestro) run_maestro=true ;;
        *) avd_names+=("$arg") ;;
    esac
done

if [[ "$run_connected" == false && "$run_maestro" == false ]]; then
    run_connected=true
fi

if [[ ${#avd_names[@]} -eq 0 ]]; then
    mapfile -t avd_names < <("$EMULATOR" -list-avds)
fi

if [[ ${#avd_names[@]} -eq 0 ]]; then
    echo "No AVDs found (and none given on the command line)." >&2
    exit 1
fi

mkdir -p "$RESULTS_ROOT"

declare -a summary_avd
declare -a summary_status
declare -a summary_duration

boot_avd() {
    local avd="$1"
    local extra_flags="$2"
    echo "[$avd] booting headless…"
    # shellcheck disable=SC2086
    "$EMULATOR" -avd "$avd" -no-snapshot -no-audio -no-boot-anim -no-window $extra_flags \
        > "$RESULTS_ROOT/$avd/emulator.log" 2>&1 &
    EMULATOR_PID=$!

    "$ADB" -s "emulator-$EMU_PORT" wait-for-device 2>/dev/null || true

    local waited=0
    while (( waited < BOOT_TIMEOUT_S )); do
        local booted
        booted="$("$ADB" -s "emulator-$EMU_PORT" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')"
        if [[ "$booted" == "1" ]]; then
            echo "[$avd] booted after ${waited}s"
            return 0
        fi
        sleep 5
        waited=$((waited + 5))
    done
    echo "[$avd] boot timed out after ${BOOT_TIMEOUT_S}s" >&2
    return 1
}

run_one_avd() {
    local avd="$1"
    local avd_dir="$RESULTS_ROOT/$avd"
    mkdir -p "$avd_dir"
    local start_ts
    start_ts=$(date +%s)
    local status="pass"

    # Allocate a deterministic-ish port per run so ANDROID_SERIAL is known.
    EMU_PORT=5554
    local serial="emulator-$EMU_PORT"

    local extra_flags=""
    if [[ "$avd" == *"26"* ]]; then
        extra_flags="-no-snapshot-load"
    fi

    if ! boot_avd "$avd" "$extra_flags"; then
        status="fail-boot"
    else
        export ANDROID_SERIAL="$serial"

        echo "[$avd] installing debug APK…"
        if ! "$ADB" -s "$serial" install -g "$DEBUG_APK" > "$avd_dir/install.log" 2>&1; then
            status="fail-install"
        fi

        if [[ "$status" == "pass" ]]; then
            echo "[$avd] granting overlay appop…"
            "$ADB" -s "$serial" shell appops set "$PACKAGE_NAME" SYSTEM_ALERT_WINDOW allow

            echo "[$avd] seeding settings…"
            "$ADB" -s "$serial" shell am broadcast -n \
                "$PACKAGE_NAME/com.gotcha.debug.TestHooksReceiver" \
                -a com.gotcha.debug.SEED_SETTINGS \
                --es base_url "http://localhost:8080/v1/" --es api_key test --es model test-model \
                > "$avd_dir/seed.log" 2>&1

            if [[ "$run_connected" == true ]]; then
                echo "[$avd] running connectedDebugAndroidTest…"
                if ! (cd "$REPO_ROOT" && ./gradlew :app:connectedDebugAndroidTest --stacktrace \
                    > "$avd_dir/connected-test.log" 2>&1); then
                    status="fail-connected"
                fi
                cp -r "$REPO_ROOT/app/build/reports/androidTests" "$avd_dir/" 2>/dev/null || true
                cp -r "$REPO_ROOT/app/build/outputs/androidTest-results" "$avd_dir/" 2>/dev/null || true
            fi

            if [[ "$run_maestro" == true && "$status" == "pass" ]]; then
                echo "[$avd] running Maestro flows…"
                if ! "$REPO_ROOT/scripts/maestro_run.sh" > "$avd_dir/maestro.log" 2>&1; then
                    status="fail-maestro"
                fi
            fi
        fi

        "$ADB" -s "$serial" logcat -d > "$avd_dir/logcat.txt" 2>/dev/null || true
        "$ADB" -s "$serial" emu kill 2>/dev/null || true
        wait "$EMULATOR_PID" 2>/dev/null || true
    fi

    local end_ts
    end_ts=$(date +%s)
    summary_avd+=("$avd")
    summary_status+=("$status")
    summary_duration+=("$((end_ts - start_ts))s")
}

overall_exit=0
for avd in "${avd_names[@]}"; do
    run_one_avd "$avd"
    if [[ "${summary_status[-1]}" != "pass" ]]; then
        overall_exit=1
    fi
done

echo
echo "==== Matrix summary ===="
printf "%-24s %-16s %s\n" "AVD" "Status" "Duration"
for i in "${!summary_avd[@]}"; do
    printf "%-24s %-16s %s\n" "${summary_avd[$i]}" "${summary_status[$i]}" "${summary_duration[$i]}"
done

exit $overall_exit
