#!/usr/bin/env bash
# Ephemeral CI emulator only. Never connects to or modifies a user's phone.
set -euo pipefail
SDK_ROOT="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}"
test -n "$SDK_ROOT"
SDKMANAGER="$SDK_ROOT/cmdline-tools/latest/bin/sdkmanager"
AVDMANAGER="$SDK_ROOT/cmdline-tools/latest/bin/avdmanager"
# Hardware access is a separately approved, manual CI action. No broad chmod/group changes.
if ! { [ -r /dev/kvm ] && [ -w /dev/kvm ]; }; then
  if [ "${ALLOW_KVM:-false}" = true ] && [ -e /dev/kvm ] && command -v setfacl >/dev/null; then
    sudo setfacl -m "u:$(id -un):rw" /dev/kvm
  fi
fi
if ! { [ -r /dev/kvm ] && [ -w /dev/kvm ]; }; then
  echo 'DEVICE_TEST_NOT_RUN: approved KVM access unavailable'; exit 2
fi
"$SDKMANAGER" "emulator" "system-images;android-30;default;x86_64"
export ANDROID_AVD_HOME="${RUNNER_TEMP:-$PWD/.toolchains}/scoreledger-avd"
export ANDROID_USER_HOME="${RUNNER_TEMP:-$PWD/.toolchains}/scoreledger-android-user"
mkdir -p "$ANDROID_AVD_HOME" "$ANDROID_USER_HOME"
AVD_NAME="scoreledger_ci_${GITHUB_RUN_ID:-local}"
printf 'no\n' | "$AVDMANAGER" create avd -n "$AVD_NAME" -k 'system-images;android-30;default;x86_64' --device pixel_2
"$SDK_ROOT/emulator/emulator" -list-avds | grep -Fx "$AVD_NAME"
mkdir -p evidence
ACCEL=on
echo "EMULATOR_ACCEL=$ACCEL"
"$SDK_ROOT/emulator/emulator" -avd "$AVD_NAME" -no-window -no-audio -no-boot-anim -no-snapshot -gpu swiftshader_indirect -accel "$ACCEL" > evidence/emulator-runtime.log 2>&1 &
EMU_PID=$!
trap 'kill "$EMU_PID" 2>/dev/null || true' EXIT
export PATH="$SDK_ROOT/platform-tools:$PATH"
adb start-server
BOOTED=false
DEADLINE=$((SECONDS + 600))
i=0
while [ "$SECONDS" -lt "$DEADLINE" ]; do
  i=$((i + 1))
  if [ $((i % 6)) -eq 1 ]; then echo "Waiting for Android boot; elapsed ${SECONDS}s"; fi
  kill -0 "$EMU_PID" 2>/dev/null || { tail -50 evidence/emulator-runtime.log; echo 'EMULATOR_EXITED'; exit 1; }
  if [ "$(timeout 10 adb -s emulator-5554 shell getprop sys.boot_completed 2>/dev/null | tr -d '\r' || true)" = 1 ]; then BOOTED=true; break; fi
  sleep 5
done
[ "$BOOTED" = true ] || { echo 'EMULATOR_BOOT_TIMEOUT'; exit 1; }
# Boot completion is not package-manager readiness.
for i in $(seq 1 30); do
  if timeout 10 adb -s emulator-5554 shell pm path android >/dev/null 2>&1; then break; fi
  sleep 2
done
./gradlew --no-daemon :app:connectedLocalBetaDebugAndroidTest 2>&1 | tee evidence/android-instrumentation.log
if adb -s emulator-5554 shell test -d /sdcard/Android/data/com.example.scoreledger.beta.debug/files/pdf-evidence; then
  adb -s emulator-5554 pull /sdcard/Android/data/com.example.scoreledger.beta.debug/files/pdf-evidence evidence/pdf-artifacts
else
  echo 'PDF_EVIDENCE_NOT_CAPTURED'
fi
adb -s emulator-5554 shell am start -W -n com.example.scoreledger.beta.debug/com.example.scoreledger.MainActivity > evidence/emulator-launch.log
sleep 3
adb -s emulator-5554 shell pidof com.example.scoreledger.beta.debug > evidence/emulator-pid.txt
test -s evidence/emulator-pid.txt
adb -s emulator-5554 exec-out screencap -p > evidence/emulator-start.png
adb -s emulator-5554 shell getprop ro.build.version.sdk > evidence/emulator-api.txt
adb -s emulator-5554 shell pm list packages com.google.android.gms > evidence/emulator-gms.txt
echo 'Emulator instrumentation and launch completed; physical devices are NOT tested.'
