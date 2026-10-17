#!/usr/bin/env bash
# Exact delivered artifacts, disposable hosted-runner AVD only. Never rebuild/re-sign.
set -euo pipefail

check_instrumentation() {
  python3 - "$1" <<'PY'
import pathlib, re, sys
text = pathlib.Path(sys.argv[1]).read_text(errors='replace').replace('\r', '')
# adb/am can exit zero on crashes/assertion failures. Require the runner's final
# success code AND positive JUnit completion; reject errors anywhere in the stream.
codes = re.findall(r'^INSTRUMENTATION_CODE:\s*(-?\d+)\s*$', text, re.M)
counts = re.findall(r'^OK \((\d+) tests?\)\s*$', text, re.M)
fail = re.search(r'FAILURES!!!|INSTRUMENTATION_FAILED|INSTRUMENTATION_ABORTED|Process crashed|shortMsg=|^INSTRUMENTATION_STATUS_CODE:\s*-[12]\s*$', text, re.M)
if codes != ['-1'] or len(counts) != 1 or int(counts[0]) < 1 or fail:
    print('INSTRUMENTATION_FAILED_OR_INCOMPLETE', file=sys.stderr)
    sys.exit(1)
statuses = re.findall(r'^INSTRUMENTATION_STATUS_CODE:\s*(-?\d+)\s*$', text, re.M)
print('INSTRUMENTATION_VERIFIED_PASS reported_tests=' + counts[0] +
      ' completed=' + str(statuses.count('0')) +
      ' skipped_or_assumptions=' + str(statuses.count('-3') + statuses.count('-4')))
PY
}
# Offline parser regression checks can use synthetic logs without touching devices.
if [ "${1:-}" = --check-instrumentation ]; then
  check_instrumentation "$2"
  exit
fi

EVIDENCE="$PWD/evidence/signed-device"
mkdir -p "$EVIDENCE"
exec > >(tee -a "$EVIDENCE/acceptance.log") 2>&1
API="${ANDROID_API:-30}"
case "$API" in 26|30|36) ;; *) echo 'Unsupported API'; exit 2;; esac
SDK_ROOT="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}"
: "${SDK_ROOT:?Android SDK required}"
SDKMANAGER="$SDK_ROOT/cmdline-tools/latest/bin/sdkmanager"
AVDMANAGER="$SDK_ROOT/cmdline-tools/latest/bin/avdmanager"
CERT=bc67f6b0ccff15581b5c08ca90df4d877c4067e3ed33bd183caa071b93954e1c
PACKAGE=com.example.scoreledger.beta.debug
TEST_PACKAGE="$PACKAGE.test"
RUNNER="$TEST_PACKAGE/androidx.test.runner.AndroidJUnitRunner"
EMU_PID=''
SERIAL=emulator-5580
export PATH="$SDK_ROOT/platform-tools:$PATH"
collect_and_stop() {
  local result=$?
  trap - EXIT
  set +e
  if [ -n "$EMU_PID" ] && kill -0 "$EMU_PID" 2>/dev/null; then
    timeout 15 adb -s "$SERIAL" exec-out screencap -p > "$EVIDENCE/final-screen.png"
    timeout 20 adb -s "$SERIAL" logcat -d > "$EVIDENCE/logcat.txt"
    timeout 10 adb -s "$SERIAL" shell getprop > "$EVIDENCE/device-properties.txt"
    timeout 30 adb -s "$SERIAL" pull "/sdcard/Android/data/$PACKAGE/files/pdf-evidence" "$EVIDENCE/pdf-artifacts"
    timeout 30 adb -s "$SERIAL" pull "/sdcard/Android/data/$PACKAGE/files/ui-evidence" "$EVIDENCE/ui-artifacts"
    kill "$EMU_PID" 2>/dev/null
    wait "$EMU_PID" 2>/dev/null
  fi
  printf 'acceptance_exit_code=%s\n' "$result" > "$EVIDENCE/result.txt"
  exit "$result"
}
trap collect_and_stop EXIT
printf 'NOT_RUN: version upgrade requires a distinct older signed APK; reinstall is not an upgrade.\n' > "$EVIDENCE/version-upgrade.txt"
printf 'NOT_RUN: guarded reinstall seed/verify instrumentation not selected.\n' > "$EVIDENCE/reinstall-retention.txt"

"$SDKMANAGER" 'build-tools;35.0.0' platform-tools emulator "system-images;android-$API;default;x86_64"
APKSIGNER="$SDK_ROOT/build-tools/35.0.0/apksigner"
AAPT="$SDK_ROOT/build-tools/35.0.0/aapt"
mapfile -d '' APPS < <(find signed-input/app -type f -name '*.apk' -print0)
mapfile -d '' TESTS < <(find signed-input/test -type f -name '*androidTest.apk' -print0)
[ "${#APPS[@]}" = 1 ] && [ "${#TESTS[@]}" = 1 ] || { echo 'Expected exactly one app APK and one instrumentation APK'; exit 1; }
APP_APK="${APPS[0]}"
TEST_APK="${TESTS[0]}"
verify_apk() {
  local apk="$1" label="$2" expected_package="$3"
  "$APKSIGNER" verify --verbose --print-certs "$apk" > "$EVIDENCE/$label-signature.txt"
  python3 - "$EVIDENCE/$label-signature.txt" "$CERT" <<'PY'
import pathlib, re, sys
certs = re.findall(r'^Signer #\d+ certificate SHA-256 digest: (\S+)$', pathlib.Path(sys.argv[1]).read_text(), re.M)
assert [c.lower() for c in certs] == [sys.argv[2]], 'Expected exactly the fixed internal signer'
PY
  "$AAPT" dump badging "$apk" > "$EVIDENCE/$label-badging.txt"
  grep -q "^package: name='$expected_package' " "$EVIDENCE/$label-badging.txt"
}
verify_apk "$APP_APK" app "$PACKAGE"
verify_apk "$TEST_APK" test "$TEST_PACKAGE"
sha256sum "$APP_APK" "$TEST_APK" | tee "$EVIDENCE/apk-sha256.txt"
python3 - "$EVIDENCE/artifact-provenance.json" "$APP_APK" "$TEST_APK" "$API" "$CERT" <<'PY'
import hashlib, json, os, pathlib, sys
paths = [pathlib.Path(p) for p in sys.argv[2:4]]
data = {'repository': os.getenv('GITHUB_REPOSITORY'), 'artifactRunId': os.getenv('ARTIFACT_RUN_ID'),
        'acceptanceRunId': os.getenv('GITHUB_RUN_ID'), 'acceptanceSourceSha': os.getenv('GITHUB_SHA'),
        'api': int(sys.argv[4]), 'certificateSha256': sys.argv[5], 'rebuilt': False,
        'apks': [{'path': str(p), 'bytes': p.stat().st_size, 'sha256': hashlib.sha256(p.read_bytes()).hexdigest()} for p in paths]}
pathlib.Path(sys.argv[1]).write_text(json.dumps(data, indent=2) + '\n')
PY

if ! { [ -r /dev/kvm ] && [ -w /dev/kvm ]; }; then
  if [ "${ALLOW_KVM:-false}" = true ] && [ -e /dev/kvm ] && command -v setfacl >/dev/null; then
    sudo setfacl -m "u:$(id -un):rw" /dev/kvm
  fi
fi
[ -r /dev/kvm ] && [ -w /dev/kvm ] || { echo 'DEVICE_TEST_NOT_RUN: approved hardware acceleration unavailable'; exit 2; }
AVD_ROOT=$(mktemp -d "${RUNNER_TEMP:-/tmp}/scoreledger-signed-avd.XXXXXX")
export ANDROID_AVD_HOME="$AVD_ROOT/avd"
export ANDROID_USER_HOME="$AVD_ROOT/user"
export ANDROID_EMULATOR_HOME="$AVD_ROOT/emulator"
mkdir -p "$ANDROID_AVD_HOME" "$ANDROID_USER_HOME" "$ANDROID_EMULATOR_HOME"
AVD_NAME=scoreledger_signed_acceptance
printf 'no\n' | "$AVDMANAGER" create avd -n "$AVD_NAME" -k "system-images;android-$API;default;x86_64" --device pixel_2
"$SDK_ROOT/emulator/emulator" -list-avds | grep -Fx "$AVD_NAME"
adb start-server
# Refuse to touch any pre-existing device occupying our chosen emulator serial.
if adb devices | grep -q "^$SERIAL[[:space:]]"; then echo 'Emulator serial already occupied'; exit 1; fi
"$SDK_ROOT/emulator/emulator" -avd "$AVD_NAME" -port 5580 -no-window -no-audio -no-boot-anim -no-snapshot -wipe-data -gpu swiftshader_indirect -accel on > "$EVIDENCE/emulator-runtime.log" 2>&1 &
EMU_PID=$!
DEADLINE=$((SECONDS + 600))
READY=false
while [ "$SECONDS" -lt "$DEADLINE" ]; do
  kill -0 "$EMU_PID" 2>/dev/null || { echo 'EMULATOR_EXITED'; exit 1; }
  if [ "$(timeout 5 adb -s "$SERIAL" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r' || true)" = 1 ] &&
     timeout 5 adb -s "$SERIAL" shell pm path android 2>/dev/null | grep -q '^package:'; then
    READY=true
    break
  fi
  sleep 3
done
[ "$READY" = true ] || { echo 'BOOT_OR_PACKAGE_MANAGER_TIMEOUT'; exit 1; }
timeout 10 adb -s "$SERIAL" shell pm list packages com.google.android.gms | tr -d '\r' > "$EVIDENCE/gms-packages.txt"
if grep -q '^package:com.google.android.gms$' "$EVIDENCE/gms-packages.txt"; then
  echo 'Unexpected GMS in AOSP default image'; exit 1
fi
printf 'Verified package manager ready; com.google.android.gms absent.\n' > "$EVIDENCE/aosp-verification.txt"

# Only this new AVD is modified. No uninstall, clear, or physical-device commands.
timeout 120 adb -s "$SERIAL" install "$APP_APK" | tee "$EVIDENCE/app-install.txt"
timeout 120 adb -s "$SERIAL" install "$TEST_APK" | tee "$EVIDENCE/test-install.txt"
adb -s "$SERIAL" shell pm list instrumentation | tr -d '\r' > "$EVIDENCE/instrumentation-target.txt"
grep -Fx "instrumentation:$RUNNER (target=$PACKAGE)" "$EVIDENCE/instrumentation-target.txt"
set +e
timeout 900 adb -s "$SERIAL" shell am instrument -w -r "$RUNNER" 2>&1 | tee "$EVIDENCE/full-instrumentation.txt"
INSTRUMENT_EXIT=${PIPESTATUS[0]}
set -e
[ "$INSTRUMENT_EXIT" = 0 ] || { echo "Instrumentation transport/timeout failure: $INSTRUMENT_EXIT"; exit 1; }
check_instrumentation "$EVIDENCE/full-instrumentation.txt"
timeout 30 adb -s "$SERIAL" shell am start -W -n "$PACKAGE/com.example.scoreledger.MainActivity" > "$EVIDENCE/launch.txt"
sleep 3
timeout 10 adb -s "$SERIAL" shell pidof "$PACKAGE" > "$EVIDENCE/app-pid.txt"
test -s "$EVIDENCE/app-pid.txt"
timeout 15 adb -s "$SERIAL" exec-out screencap -p > "$EVIDENCE/launch-screen.png"
test -s "$EVIDENCE/launch-screen.png"
if [ "${RUN_REINSTALL_RETENTION:-false}" = true ]; then
  # This opt-in only writes guarded synthetic data to the disposable emulator.
  # It deliberately fails rather than clearing any pre-existing database content.
  NONCE=$(python3 -c 'import secrets; print(secrets.token_hex(24))')
  printf 'RUNNING: same-APK reinstall retention, nonce=%s\n' "$NONCE" > "$EVIDENCE/reinstall-retention.txt"
  retention_phase() {
    local mode="$1" method="$2"
    set +e
    timeout 180 adb -s "$SERIAL" shell am instrument -w -r \
      -e class "com.example.scoreledger.UpgradeRetentionTest#$method" \
      -e upgradeRetention "$mode" -e allowPersistentSyntheticData true \
      -e upgradeNonce "$NONCE" "$RUNNER" 2>&1 | tee "$EVIDENCE/retention-$mode.txt"
    local status=${PIPESTATUS[0]}
    set -e
    [ "$status" = 0 ] || return 1
    check_instrumentation "$EVIDENCE/retention-$mode.txt"
    # Require exactly one completed selected method. An assumption is NOT proof.
    python3 - "$EVIDENCE/retention-$mode.txt" "$method" <<'PYRETENTION'
import pathlib, re, sys
text = pathlib.Path(sys.argv[1]).read_text().replace('\r', '')
assert re.findall(r'^OK \((\d+) tests?\)\s*$', text, re.M) == ['1']
statuses = re.findall(r'^INSTRUMENTATION_STATUS_CODE:\s*(-?\d+)\s*$', text, re.M)
assert statuses.count('0') == 1 and '-3' not in statuses and '-4' not in statuses, 'Skipped or unexpected test count'
blocks = re.split(r'^INSTRUMENTATION_STATUS_CODE:\s*(-?\d+)\s*$', text, flags=re.M)
completed = [blocks[i - 1] for i in range(1, len(blocks), 2) if blocks[i] == '0']
assert len(completed) == 1
assert re.search(r'^INSTRUMENTATION_STATUS: class=com\.example\.scoreledger\.UpgradeRetentionTest$', completed[0], re.M)
assert re.search(r'^INSTRUMENTATION_STATUS: test=' + re.escape(sys.argv[2]) + '$', completed[0], re.M)
PYRETENTION
    timeout 15 adb -s "$SERIAL" shell run-as "$PACKAGE" cat "no_backup/upgrade-retention-$NONCE.json" > "$EVIDENCE/retention-$mode-marker.json"
    test -s "$EVIDENCE/retention-$mode-marker.json"
  }
  retention_phase seed seedPersistentSyntheticData
  timeout 120 adb -s "$SERIAL" install -r "$APP_APK" | tee "$EVIDENCE/reinstall.txt"
  retention_phase verify verifyPersistentSyntheticData
  cmp "$EVIDENCE/retention-seed-marker.json" "$EVIDENCE/retention-verify-marker.json"
  printf 'PASSED: same downloaded fixed-signed APK reinstalled with install -r; guarded synthetic data verified. NOT a version upgrade.\n' > "$EVIDENCE/reinstall-retention.txt"
fi
echo 'SIGNED_ARTIFACT_DEVICE_ACCEPTANCE_PASSED; physical devices and version upgrades NOT tested.'
