#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="${1:?repository root is required}"
CONSUMER_BUILD_DIR="${2:?consumer build directory is required}"
REPORT_DIR="$ROOT_DIR/build/reports/android-device"
MAIN_PACKAGE="saien.magrathea.samples.android"
TEST_PACKAGE="saien.magrathea.samples.android.device.test"
RUNNER="saien.magrathea.samples.android.MagratheaDeviceTestInstrumentation"
READY_FILE="files/magrathea-device-performance-ready"
HTTP_PORT="48765"
HTTP_SERVER_SOURCE="$ROOT_DIR/tooling/android-device/DeviceHttpProbeServer.java"
INSTALL_TIMEOUT_SECONDS=60
UNINSTALL_TIMEOUT_SECONDS=30

command -v adb >/dev/null 2>&1 || {
    echo "adb is required" >&2
    exit 1
}

run_with_timeout() {
    local timeout_seconds="$1"
    shift
    "$@" &
    local command_pid=$!
    (
        sleep "$timeout_seconds"
        kill -TERM "$command_pid" >/dev/null 2>&1 || exit 0
        sleep 1
        kill -KILL "$command_pid" >/dev/null 2>&1 || true
    ) &
    local watchdog_pid=$!
    local status=0
    wait "$command_pid" || status=$?
    kill "$watchdog_pid" >/dev/null 2>&1 || true
    wait "$watchdog_pid" >/dev/null 2>&1 || true
    return "$status"
}

resolve_serial() {
    local requested="${MAGRATHEA_ANDROID_SERIAL:-${ANDROID_SERIAL:-}}"
    if [[ -n "$requested" ]]; then
        [[ "$(adb -s "$requested" get-state 2>/dev/null)" == "device" ]] || {
            echo "Requested Android device is not online: $requested" >&2
            exit 1
        }
        printf '%s\n' "$requested"
        return
    fi

    local devices
    devices="$(adb devices | awk 'NR > 1 && $2 == "device" { print $1 }')"
    [[ "$(printf '%s\n' "$devices" | sed '/^$/d' | wc -l | tr -d ' ')" == "1" ]] || {
        echo "Exactly one authorized Android device is required; set MAGRATHEA_ANDROID_SERIAL when multiple devices are connected" >&2
        exit 1
    }
    printf '%s\n' "$devices"
}

SERIAL="$(resolve_serial)"
CURRENT_USER="$(adb -s "$SERIAL" shell am get-current-user | tr -d '\r')"
API_LEVEL="$(adb -s "$SERIAL" shell getprop ro.build.version.sdk | tr -d '\r')"
MODEL="$(adb -s "$SERIAL" shell getprop ro.product.model | tr -d '\r' | tr ' ' '_')"
ABI="$(adb -s "$SERIAL" shell getprop ro.product.cpu.abi | tr -d '\r')"
SECURITY_PATCH="$(adb -s "$SERIAL" shell getprop ro.build.version.security_patch | tr -d '\r')"

[[ "$API_LEVEL" =~ ^[0-9]+$ && "$API_LEVEL" -ge 24 ]] || {
    echo "Android API 24 or newer is required; found '$API_LEVEL'" >&2
    exit 1
}

MAIN_APK="$CONSUMER_BUILD_DIR/outputs/apk/debug/magrathea-published-android-consumer-debug.apk"
TEST_APK="$CONSUMER_BUILD_DIR/outputs/apk/androidTest/debug/magrathea-published-android-consumer-debug-androidTest.apk"
[[ -f "$MAIN_APK" ]] || {
    echo "Main device-test APK is missing: $MAIN_APK" >&2
    exit 1
}
[[ -f "$TEST_APK" ]] || {
    echo "Instrumentation APK is missing: $TEST_APK" >&2
    exit 1
}

if adb -s "$SERIAL" shell pm path --user "$CURRENT_USER" "$MAIN_PACKAGE" | grep -q '^package:'; then
    echo "Refusing to replace pre-existing package $MAIN_PACKAGE; uninstall it explicitly before running the device gate" >&2
    exit 1
fi
if adb -s "$SERIAL" shell pm path --user "$CURRENT_USER" "$TEST_PACKAGE" | grep -q '^package:'; then
    echo "Refusing to replace pre-existing package $TEST_PACKAGE; uninstall it explicitly before running the device gate" >&2
    exit 1
fi

rm -rf "$REPORT_DIR"
mkdir -p "$REPORT_DIR"
INSTALLED_MAIN=0
INSTALLED_TEST=0
HTTP_SERVER_PID=""
REVERSE_ACTIVE=0
cleanup() {
    if [[ "$REVERSE_ACTIVE" == "1" ]]; then
        adb -s "$SERIAL" reverse --remove "tcp:$HTTP_PORT" >/dev/null 2>&1 || true
    fi
    if [[ -n "$HTTP_SERVER_PID" ]]; then
        kill "$HTTP_SERVER_PID" >/dev/null 2>&1 || true
        wait "$HTTP_SERVER_PID" >/dev/null 2>&1 || true
    fi
    adb -s "$SERIAL" shell am force-stop --user "$CURRENT_USER" "$MAIN_PACKAGE" >/dev/null 2>&1 || true
    if [[ "$INSTALLED_TEST" == "1" ]]; then
        run_with_timeout "$UNINSTALL_TIMEOUT_SECONDS" \
            adb -s "$SERIAL" uninstall "$TEST_PACKAGE" >/dev/null 2>&1 || true
    fi
    if [[ "$INSTALLED_MAIN" == "1" ]]; then
        run_with_timeout "$UNINSTALL_TIMEOUT_SECONDS" \
            adb -s "$SERIAL" uninstall "$MAIN_PACKAGE" >/dev/null 2>&1 || true
    fi
}
trap cleanup EXIT

INSTALLED_MAIN=1
run_with_timeout "$INSTALL_TIMEOUT_SECONDS" \
    adb -s "$SERIAL" install --user "$CURRENT_USER" -r -t "$MAIN_APK" || {
    echo "Main test APK installation failed or exceeded ${INSTALL_TIMEOUT_SECONDS}s" >&2
    exit 1
}
INSTALLED_TEST=1
run_with_timeout "$INSTALL_TIMEOUT_SECONDS" \
    adb -s "$SERIAL" install --user "$CURRENT_USER" -r -t "$TEST_APK" || {
    echo "Instrumentation APK installation failed or exceeded ${INSTALL_TIMEOUT_SECONDS}s" >&2
    exit 1
}

adb -s "$SERIAL" shell cmd package list instrumentation "$MAIN_PACKAGE" | tee "$REPORT_DIR/instrumentation.txt"
grep -Fq "instrumentation:$TEST_PACKAGE/$RUNNER (target=$MAIN_PACKAGE)" "$REPORT_DIR/instrumentation.txt" || {
    echo "Expected instrumentation registration was not found" >&2
    exit 1
}

run_stage() {
    local stage="$1"
    local output="$REPORT_DIR/stage-$stage.txt"
    shift
    adb -s "$SERIAL" shell am instrument -w -r \
        -e stage "$stage" \
        "$@" \
        "$TEST_PACKAGE/$RUNNER" | tr -d '\r' | tee "$output"
    grep -Fq "MAGRATHEA_ANDROID_DEVICE_STAGE_PASS stage=$stage" "$output" || {
        echo "Android device stage failed: $stage" >&2
        exit 1
    }
    grep -Fq "INSTRUMENTATION_CODE: -1" "$output" || {
        echo "Android instrumentation did not return RESULT_OK for stage: $stage" >&2
        exit 1
    }
}

run_stage write
WRITE_PID="$(
    { adb -s "$SERIAL" shell pidof "$MAIN_PACKAGE" 2>/dev/null || true; } |
        tr -d '\r' |
        awk '{print $1}'
)"
[[ -z "$WRITE_PID" ]] || {
    echo "Instrumentation process unexpectedly remained alive after write stage" >&2
    exit 1
}

adb -s "$SERIAL" shell am force-stop --user "$CURRENT_USER" "$MAIN_PACKAGE"
sleep 1
[[ -z "$({ adb -s "$SERIAL" shell pidof "$MAIN_PACKAGE" 2>/dev/null || true; } | tr -d '\r')" ]] || {
    echo "force-stop did not terminate the target process" >&2
    exit 1
}
echo "MAGRATHEA_ANDROID_FORCE_STOP_PASS" | tee "$REPORT_DIR/force-stop.txt"

run_stage read

command -v curl >/dev/null 2>&1 || {
    echo "curl is required for the Android network device gate" >&2
    exit 1
}
java "$HTTP_SERVER_SOURCE" "$HTTP_PORT" > "$REPORT_DIR/http-server.txt" 2>&1 &
HTTP_SERVER_PID=$!
for _ in $(seq 1 100); do
    if curl --fail --silent --show-error "http://127.0.0.1:$HTTP_PORT/health" 2>/dev/null | grep -Fq "device-http-ok"; then
        break
    fi
    sleep 0.1
done
curl --fail --silent --show-error "http://127.0.0.1:$HTTP_PORT/health" | grep -Fq "device-http-ok" || {
    echo "Android device probe HTTP server did not become ready" >&2
    exit 1
}
adb -s "$SERIAL" reverse "tcp:$HTTP_PORT" "tcp:$HTTP_PORT" >/dev/null
REVERSE_ACTIVE=1
run_stage network -e baseUrl "http://127.0.0.1:$HTTP_PORT"
grep -Fq "MAGRATHEA_ANDROID_GEMINI_REQUEST_OBSERVED" "$REPORT_DIR/http-server.txt" || {
    echo "Android Gemini provider request was not observed by the probe server" >&2
    exit 1
}

PERFORMANCE_OUTPUT="$REPORT_DIR/stage-performance.txt"
adb -s "$SERIAL" shell am instrument -w -r \
    -e stage performance \
    -e holdMillis 15000 \
    "$TEST_PACKAGE/$RUNNER" | tr -d '\r' > "$PERFORMANCE_OUTPUT" &
INSTRUMENT_HOST_PID=$!

READY=""
for _ in $(seq 1 100); do
    READY="$(adb -s "$SERIAL" shell run-as "$MAIN_PACKAGE" cat "$READY_FILE" 2>/dev/null | tr -d '\r' || true)"
    [[ -n "$READY" ]] && break
    sleep 0.2
done
[[ -n "$READY" ]] || {
    echo "Performance stage did not become ready" >&2
    kill "$INSTRUMENT_HOST_PID" >/dev/null 2>&1 || true
    wait "$INSTRUMENT_HOST_PID" >/dev/null 2>&1 || true
    exit 1
}
printf '%s\n' "$READY" | tee "$REPORT_DIR/performance-ready.txt"

DEVICE_PID="$(printf '%s\n' "$READY" | sed -n 's/^pid=\([0-9][0-9]*\).*/\1/p')"
[[ -n "$DEVICE_PID" ]] || {
    echo "Performance stage did not report a process ID" >&2
    exit 1
}
adb -s "$SERIAL" shell dumpsys meminfo "$MAIN_PACKAGE" > "$REPORT_DIR/meminfo.txt"
if ! adb -s "$SERIAL" shell simpleperf stat --app "$MAIN_PACKAGE" -p "$DEVICE_PID" --duration 3 > "$REPORT_DIR/simpleperf.txt" 2>&1 ||
    grep -Fq "simpleperf E " "$REPORT_DIR/simpleperf.txt" ||
    ! grep -Fq "Performance counter statistics:" "$REPORT_DIR/simpleperf.txt"; then
    echo "MAGRATHEA_ANDROID_SIMPLEPERF_UNAVAILABLE" > "$REPORT_DIR/simpleperf-status.txt"
else
    echo "MAGRATHEA_ANDROID_SIMPLEPERF_PASS" > "$REPORT_DIR/simpleperf-status.txt"
fi

wait "$INSTRUMENT_HOST_PID"
cat "$PERFORMANCE_OUTPUT"
grep -Fq "MAGRATHEA_ANDROID_DEVICE_STAGE_PASS stage=performance" "$PERFORMANCE_OUTPUT" || {
    echo "Android device performance stage failed" >&2
    exit 1
}
grep -Fq "INSTRUMENTATION_CODE: -1" "$PERFORMANCE_OUTPUT" || {
    echo "Android performance instrumentation did not return RESULT_OK" >&2
    exit 1
}

{
    echo "model=$MODEL"
    echo "api=$API_LEVEL"
    echo "abi=$ABI"
    echo "security_patch=$SECURITY_PATCH"
    echo "user=$CURRENT_USER"
    echo "stages=write,read,network,performance"
    echo "force_stop=true"
} > "$REPORT_DIR/device.txt"

SUMMARY="MAGRATHEA_ANDROID_DEVICE_PASS model=$MODEL api=$API_LEVEL abi=$ABI security_patch=$SECURITY_PATCH stages=4 force_stop=1 network=1"
printf '%s\n' "$SUMMARY" | tee "$REPORT_DIR/summary.txt"

cleanup
INSTALLED_TEST=0
INSTALLED_MAIN=0
trap - EXIT
