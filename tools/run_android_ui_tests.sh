#!/usr/bin/env bash
# Dedicated Linux runner: boot a real API 35 emulator and exercise granted/denied notifications.
set -euo pipefail
cd "$(dirname "$0")/.."
: "${ANDROID_HOME:?Android SDK is required}"
: "${RUNNER_TEMP:?Run on an isolated CI runner with a temporary directory}"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export PATH="$ANDROID_HOME/platform-tools:$ANDROID_HOME/emulator:$PATH"
export ANDROID_AVD_HOME="$RUNNER_TEMP/binario-ui-avd"
mkdir -p "$ANDROID_AVD_HOME"
test -c /dev/kvm || { echo 'Android UI tests require a Linux runner with KVM.' >&2; exit 1; }
if [ ! -r /dev/kvm ] || [ ! -w /dev/kvm ]; then
  sudo setfacl -m "u:$(id -un):rw" /dev/kvm
fi

binario_tools_dir="$RUNNER_TEMP/binario-ui-sdk-tools"
mkdir -p "$binario_tools_dir"
readarray -t binario_sdk_lock < <(python3 - <<'PY'
import json
with open('.github/ci-inputs-lock.json', encoding='utf-8') as source:
    lock = json.load(source)
tool = next(tool for tool in lock['tools'] if tool['name'] == 'AndroidCommandlineTools')
print(tool['url'])
print(tool['sha256'])
PY
)
curl --fail --location --proto '=https' --proto-redir '=https' --retry 3 --max-time 180 \
  "${binario_sdk_lock[0]}" --output "$binario_tools_dir/tools.zip"
printf '%s  %s\n' "${binario_sdk_lock[1]}" "$binario_tools_dir/tools.zip" | sha256sum --check
unzip -q "$binario_tools_dir/tools.zip" -d "$binario_tools_dir"
# avdmanager discovers packages relative to its own location, ignoring ANDROID_HOME.
# Install this verified copy inside the SDK without replacing the runner's other tools.
mkdir -p "$ANDROID_HOME/cmdline-tools"
binario_sdk_tools_path="$(mktemp -d "$ANDROID_HOME/cmdline-tools/binario-ui-19.XXXXXX")"
cp -a "$binario_tools_dir/cmdline-tools/." "$binario_sdk_tools_path/"
binario_sdk_manager="$binario_sdk_tools_path/bin/sdkmanager"
"$binario_sdk_manager" --sdk_root="$ANDROID_HOME" --licenses < <(yes 2>/dev/null) > "$RUNNER_TEMP/binario-ui-licenses.log" 2>&1
"$binario_sdk_manager" --sdk_root="$ANDROID_HOME" 'emulator' 'platform-tools' 'build-tools;35.0.0' 'system-images;android-35;google_apis;x86_64'
adb version
"$ANDROID_HOME/emulator/emulator" -accel-check
printf 'no\n' | "$binario_sdk_tools_path/bin/avdmanager" create avd --force \
  --name binario-agenda-ui --package 'system-images;android-35;google_apis;x86_64' --device pixel_2

binario_serial=emulator-5556
adb start-server
"$ANDROID_HOME/emulator/emulator" -avd binario-agenda-ui -port 5556 -no-window -no-audio \
  -gpu swiftshader -no-snapshot -no-boot-anim -camera-back none -camera-front none \
  -memory 2048 > "$RUNNER_TEMP/binario-ui-emulator.log" 2>&1 &
binario_emulator_pid=$!
binario_cleanup() {
  binario_result=$?
  timeout 10 adb -s "$binario_serial" logcat -d > "$RUNNER_TEMP/binario-ui-logcat.log" 2>&1 || true
  timeout 10 adb -s "$binario_serial" emu kill >/dev/null 2>&1 || true
  kill "$binario_emulator_pid" 2>/dev/null || true
  wait "$binario_emulator_pid" 2>/dev/null || true
  if [ "$binario_result" != 0 ]; then
    tail -80 "$RUNNER_TEMP/binario-ui-emulator.log" || true
  fi
  return "$binario_result"
}
trap binario_cleanup EXIT
binario_connected=0
for binario_attempt in {1..60}; do
  kill -0 "$binario_emulator_pid" || exit 1
  if [ "$(adb -s "$binario_serial" get-state 2>/dev/null | tr -d '\r')" = device ]; then binario_connected=1; break; fi
  sleep 2
done
test "$binario_connected" = 1 || { echo 'Emulator did not connect to adb.' >&2; exit 1; }
binario_booted=0
for binario_attempt in {1..120}; do
  kill -0 "$binario_emulator_pid" || { tail -80 "$RUNNER_TEMP/binario-ui-emulator.log"; exit 1; }
  if [ "$(adb -s "$binario_serial" shell getprop sys.boot_completed | tr -d '\r')" = 1 ]; then binario_booted=1; break; fi
  sleep 2
done
test "$binario_booted" = 1 || { tail -80 "$RUNNER_TEMP/binario-ui-emulator.log"; exit 1; }
adb -s "$binario_serial" shell input keyevent 82
adb -s "$binario_serial" shell settings put global window_animation_scale 0
adb -s "$binario_serial" shell settings put global transition_animation_scale 0
adb -s "$binario_serial" shell settings put global animator_duration_scale 0
export ANDROID_SERIAL="$binario_serial"
./gradlew --no-daemon --max-workers=2 --dependency-verification=strict :app:installDebug
adb -s "$binario_serial" shell cmd locale set-app-locales it.binario.app --locales it
adb -s "$binario_serial" shell pm grant it.binario.app android.permission.POST_NOTIFICATIONS
adb -s "$binario_serial" shell appops set it.binario.app SCHEDULE_EXACT_ALARM allow
./gradlew --no-daemon --max-workers=2 --dependency-verification=strict :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.notClass=it.binario.app.PlannedJourneyReminderPermissionTest,it.binario.app.RfiJourneyNetworkTest

# Revoke outside instrumentation: Android may terminate a process when its permission changes.
adb -s "$binario_serial" install -r app/build/outputs/apk/debug/app-debug.apk
adb -s "$binario_serial" install -r -t app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
if [ "${BINARIO_VERIFY_RFI_ROUTE:-false}" = true ]; then
  adb -s "$binario_serial" shell am instrument -w -r \
    -e class it.binario.app.RfiJourneyNetworkTest \
    it.binario.app.test/androidx.test.runner.AndroidJUnitRunner \
    | tee "$RUNNER_TEMP/binario-ui-rfi-route.log"
  grep -Fq 'OK (1 test)' "$RUNNER_TEMP/binario-ui-rfi-route.log"
  if grep -Eq 'FAILURES|INSTRUMENTATION_FAILED|Process crashed' "$RUNNER_TEMP/binario-ui-rfi-route.log"; then exit 1; fi
fi
adb -s "$binario_serial" shell am force-stop it.binario.app
adb -s "$binario_serial" shell pm revoke it.binario.app android.permission.POST_NOTIFICATIONS
adb -s "$binario_serial" shell am instrument -w -r \
  -e class it.binario.app.PlannedJourneyReminderPermissionTest \
  it.binario.app.test/androidx.test.runner.AndroidJUnitRunner \
  | tee "$RUNNER_TEMP/binario-ui-permission-denied.log"
grep -Fq 'OK (1 test)' "$RUNNER_TEMP/binario-ui-permission-denied.log"
if grep -Eq 'FAILURES|INSTRUMENTATION_FAILED|Process crashed' "$RUNNER_TEMP/binario-ui-permission-denied.log"; then exit 1; fi
