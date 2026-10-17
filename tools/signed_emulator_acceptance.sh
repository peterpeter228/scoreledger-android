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
check_boot_health() {
  python3 - "$1" <<'PYBOOT'
import pathlib, re, sys
text = pathlib.Path(sys.argv[1]).read_text(errors='replace')
if not text.strip():
    sys.exit('EMULATOR_BOOT_HEALTH_UNCONFIRMED: empty logcat')
failures = re.findall(r'^.*\bANR in com\.android\.(?:systemui|phone)(?:[ \t]|$).*$', text, re.M)
failures += re.findall(r'^.*FATAL EXCEPTION IN SYSTEM PROCESS:.*$', text, re.M)
if failures:
    print('EMULATOR_BOOT_HEALTH_FAILED: system component ANR/crash before app installation')
    print('\n'.join(failures))
    sys.exit(1)
print('EMULATOR_BOOT_HEALTH_CHECKED: no SystemUI/phone ANR in captured boot log; not a guarantee of later health')
PYBOOT
}
if [ "${1:-}" = --check-boot-health ]; then
  check_boot_health "$2"
  exit
fi
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
HEALTH_LOG_PID=''
SERIAL=emulator-5580
export PATH="$SDK_ROOT/platform-tools:$PATH"
# Read only the app's own evidence as its debuggable UID. Shell's direct adb
# pull cannot access app-scoped external storage on newer Android versions.
collect_app_evidence() {
  local name="$1" destination="$2" base archive
  local found=false
  case "$name" in pdf-evidence|ui-evidence) ;; *) return 1;; esac
  # Prefer private files if a newer test writes there; support older delivered APKs.
  for base in files "/sdcard/Android/data/$PACKAGE/files"; do
    # API 26 has shell-builtin test, not necessarily an executable for run-as.
    # All interpolated values are fixed above or allowlisted; preserve quoting
    # through adb's remote-shell join, then test as the same app UID.
    if timeout 10 adb -s "$SERIAL" shell "run-as '$PACKAGE' sh -c 'test -d \"$base/$name\" && ! test -L \"$base/$name\"'"; then
      found=true
      archive="$EVIDENCE/$name.tar"
      if ! timeout 30 adb -s "$SERIAL" exec-out run-as "$PACKAGE" tar -cf - -C "$base" "$name" > "$archive" 2> "$EVIDENCE/$name-collection.stderr"; then
        echo "EVIDENCE_COLLECTION_FAILED: $name (run-as tar)"
        return 1
      fi
      # Never extract device-controlled symlinks, special files, or traversal paths.
      # Validate the whole archive before writing regular files into a new folder.
      python3 - "$archive" "$EVIDENCE/$destination" "$name" <<'PYEVIDENCE'
import pathlib, shutil, sys, tarfile
archive, destination, expected = sys.argv[1:]
root = pathlib.Path(destination)
with tarfile.open(archive, 'r:') as tar:
    members = tar.getmembers()
    if len(members) > 1000 or sum(m.size for m in members) > 100 * 1024 * 1024:
        raise ValueError('Evidence archive exceeds limits')
    paths = set()
    for member in members:
        path = pathlib.PurePosixPath(member.name)
        if (path.is_absolute() or '..' in path.parts or not path.parts or
                path.parts[0] != expected or not (member.isdir() or member.isfile()) or
                path in paths or member.size < 0):
            raise ValueError('Unsafe evidence archive member: ' + member.name)
        paths.add(path)
    root.mkdir(parents=True, exist_ok=False)
    for member in members:
        relative = pathlib.PurePosixPath(member.name).relative_to(expected)
        target = root.joinpath(*relative.parts)
        if member.isdir():
            target.mkdir(parents=True, exist_ok=True)
        else:
            target.parent.mkdir(parents=True, exist_ok=True)
            with tar.extractfile(member) as source, target.open('xb') as output:
                shutil.copyfileobj(source, output)
    print('EVIDENCE_COLLECTED:', expected, 'regular_files=', sum(m.isfile() for m in members))
PYEVIDENCE
      return "$?"
    fi
  done
  [ "$found" = true ] || echo "EVIDENCE_NOT_PRESENT: $name"
  return 1
}

# Read-only continuous system evidence and fail-closed phase checkpoints.
health_checkpoint() {
  local phase="$1"
  [[ "$phase" =~ ^[a-z0-9-]+$ ]] || return 1
  kill -0 "$HEALTH_LOG_PID" 2>/dev/null || { echo 'HEALTH_LOG_STREAM_STOPPED'; return 1; }
  timeout 20 adb -s "$SERIAL" logcat -d > "$EVIDENCE/health-$phase-logcat.txt"
  cat "$EVIDENCE/continuous-system-logcat.txt" "$EVIDENCE/health-$phase-logcat.txt" > "$EVIDENCE/health-latest-logcat.txt"
  check_boot_health "$EVIDENCE/health-latest-logcat.txt" | tee "$EVIDENCE/health-$phase.txt"
}

# Disposable AVD ordinary input preference only: no host/user-device change and
# no app-level IMM forcing. Android16 still reports an exposed QWERTY keyboard
# with hw.keyboard=no; explicitly test the visible software-keyboard use case.
configure_visible_software_keyboard() {
  timeout 10 adb -s "$SERIAL" shell settings get secure show_ime_with_hard_keyboard > "$EVIDENCE/software-keyboard-preference-before.txt"
  timeout 10 adb -s "$SERIAL" shell settings put secure show_ime_with_hard_keyboard 1
  timeout 10 adb -s "$SERIAL" shell settings get secure show_ime_with_hard_keyboard > "$EVIDENCE/software-keyboard-preference-after.txt"
  [ "$(tr -d '\r\n' < "$EVIDENCE/software-keyboard-preference-after.txt")" = 1 ] || {
    echo 'SOFTWARE_KEYBOARD_PREFERENCE_NOT_APPLIED'; return 1;
  }
}

# Only the same nonce-owned marker read may retry; never replay business actions.
read_retention_marker() {
  local phase="$1" attempt status tmp err
  [[ "$phase" =~ ^[a-z0-9-]+$ ]] && [[ "$NONCE" =~ ^[0-9a-f]{48}$ ]] || return 1
  for attempt in 1 2; do
    tmp="$EVIDENCE/retention-$phase-marker-attempt-$attempt.tmp"
    err="$EVIDENCE/retention-$phase-marker-attempt-$attempt.stderr"
    if timeout 15 adb -s "$SERIAL" shell run-as "$PACKAGE" cat "no_backup/upgrade-retention-$NONCE.json" > "$tmp" 2> "$err"; then
      status=0
    else
      status=$?
    fi
    printf '%s\n' "$status" > "$EVIDENCE/retention-$phase-marker-attempt-$attempt.exit"
    if [ "$status" = 0 ]; then
      python3 - "$tmp" "$NONCE" <<'PYMARKER' || return 1
import json,pathlib,sys
raw=pathlib.Path(sys.argv[1]).read_bytes()
assert 0<len(raw)<=4096
value=json.loads(raw)
assert value['nonce']==sys.argv[2]
assert all(isinstance(value.get(k),str) and value[k] for k in ('childId','examId','draftId'))
PYMARKER
      # A valid JSON read must still equal the original exact synced marker.
      if [ "$phase" != seed ]; then cmp "$EVIDENCE/retention-seed-marker.json" "$tmp" || return 1; fi
      mv "$tmp" "$EVIDENCE/retention-$phase-marker.json" || return 1
      return 0
    fi
    echo "RETENTION_MARKER_READ_FAILED phase=$phase attempt=$attempt exit=$status"
    # Only the observed silent255 transport/process failure is eligible. Explicit
    # stderr (including access errors), partial data, and all other errors fail.
    [ "$attempt" = 1 ] && [ "$status" = 255 ] && [ ! -s "$err" ] && [ ! -s "$tmp" ] || return 1
    timeout 10 adb -s "$SERIAL" get-state > "$EVIDENCE/retention-$phase-retry-device.txt" || return 1
    [ "$(tr -d '\r\n' < "$EVIDENCE/retention-$phase-retry-device.txt")" = device ] || return 1
    timeout 10 adb -s "$SERIAL" shell pm path "$PACKAGE" > "$EVIDENCE/retention-$phase-retry-package.txt" || return 1
    grep -q '^package:' "$EVIDENCE/retention-$phase-retry-package.txt" || return 1
    health_checkpoint "marker-retry-$phase" || return 1
  done
  return 1
}

capture_input_diagnostics() {
  local phase="$1"
  case "$phase" in pre-install|final) ;; *) return 1;; esac
  timeout 10 adb -s "$SERIAL" shell settings get secure show_ime_with_hard_keyboard > "$EVIDENCE/$phase-show-ime-with-hard-keyboard.txt"
  timeout 15 adb -s "$SERIAL" shell dumpsys input_method > "$EVIDENCE/$phase-input-method.txt"
  timeout 15 adb -s "$SERIAL" shell dumpsys input > "$EVIDENCE/$phase-input.txt"
  timeout 15 adb -s "$SERIAL" shell dumpsys window > "$EVIDENCE/$phase-window.txt"
}

collect_and_stop() {
  local result=$?
  trap - EXIT
  set +e
  if [ -n "$EMU_PID" ] && kill -0 "$EMU_PID" 2>/dev/null; then
    timeout 15 adb -s "$SERIAL" exec-out screencap -p > "$EVIDENCE/final-screen.png"
    timeout 20 adb -s "$SERIAL" logcat -d > "$EVIDENCE/logcat.txt"
    timeout 10 adb -s "$SERIAL" shell getprop > "$EVIDENCE/device-properties.txt"
    capture_input_diagnostics final
    [ -d "$EVIDENCE/pdf-artifacts" ] || collect_app_evidence pdf-evidence pdf-artifacts
    [ -d "$EVIDENCE/ui-artifacts" ] || collect_app_evidence ui-evidence ui-artifacts
    kill "$EMU_PID" 2>/dev/null
    wait "$EMU_PID" 2>/dev/null
  fi
  if [ -n "$HEALTH_LOG_PID" ]; then
    kill "$HEALTH_LOG_PID" 2>/dev/null
    wait "$HEALTH_LOG_PID" 2>/dev/null
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
import hashlib, json, os, pathlib, re, sys
paths = [pathlib.Path(p) for p in sys.argv[2:4]]
root = pathlib.Path(sys.argv[1]).parent
app_run_id = os.environ['ARTIFACT_RUN_ID']
test_run_id = os.getenv('TEST_ARTIFACT_RUN_ID') or app_run_id
repository = os.environ['GITHUB_REPOSITORY']
def source_run(name, expected_id):
    assert re.fullmatch(r'[1-9][0-9]*', expected_id), 'Invalid run ID'
    run = json.loads((root / (name + '-source-run.json')).read_text())
    assert str(run['id']) == expected_id, 'Run metadata ID mismatch'
    assert run['repository'] == repository, 'Artifact source repository mismatch'
    assert re.fullmatch(r'[a-fA-F0-9]{40}', run['head_sha']), 'Missing source commit SHA'
    return run
app_source = source_run('app', app_run_id)
test_source = source_run('test', test_run_id)
assert app_source['head_sha'] == test_source['head_sha'] == os.environ['GITHUB_SHA'], 'Candidate app, test APK and acceptance source commits must match'
if app_source['conclusion'] == test_source['conclusion'] == 'success':
    build_job = None
else:
    # A successful prerequisite job in this same run is independently verified;
    # the enclosing run cannot conclude until its device matrix finishes.
    from tools.current_round_guard import verify_build_job
    assert app_run_id == test_run_id == os.environ['GITHUB_RUN_ID'], 'Unfinished external source run forbidden'
    build_job = verify_build_job(root, app_run_id, app_source['head_sha'])
apks = [{'role': role, 'path': str(p), 'bytes': p.stat().st_size,
         'sha256': hashlib.sha256(p.read_bytes()).hexdigest()}
        for role, p in zip(('app', 'instrumentation'), paths)]
pin = os.getenv('EXPECTED_APP_SHA256', '').lower()
assert re.fullmatch(r'[a-f0-9]{64}', pin), 'Required app APK SHA-256 pin missing/invalid'
assert not pin or apks[0]['sha256'] == pin, 'App APK does not match expected SHA-256'
data = {'repository': repository, 'artifactRunId': app_run_id,
        'appArtifactRunId': app_run_id, 'testArtifactRunId': test_run_id,
        'appSourceSha': app_source['head_sha'], 'testSourceSha': test_source['head_sha'],
        'mixedArtifactRuns': app_run_id != test_run_id,
        'expectedAppSha256': pin or None, 'appHashPinVerified': bool(pin),
        'acceptanceRunId': os.getenv('GITHUB_RUN_ID'), 'acceptanceSourceSha': os.getenv('GITHUB_SHA'),
        'api': int(sys.argv[4]), 'variant': 'localBetaDebug', 'package': 'com.example.scoreledger.beta.debug', 'certificateSha256': sys.argv[5], 'rebuilt': False,
        'packageBadging': (root / 'app-badging.txt').read_text().splitlines()[0],
        'requiredManifestSha256': hashlib.sha256(pathlib.Path('tools/required_device_acceptance.json').read_bytes()).hexdigest(),
        'apks': apks,
        'successfulPrerequisiteBuildJob': build_job,
        'sourceCompatibilityReview': 'Separate review required for mixed app/test source runs; this script verifies identities, not source equivalence.'}
pathlib.Path(sys.argv[1]).write_text(json.dumps(data, indent=2) + '\n')
PY

# Recheck immediately before any KVM ACL grant, not just when the job starts.
if [ -n "${CURRENT_ROUND_DEVICE_ACCEPTANCE:-}" ]; then
  python3 tools/current_round_guard.py "$EVIDENCE/app-badging.txt"
fi
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
# Explicit touch-only phone fixture, not a claim about the prior IME root cause.
# Preserve both configurations; never alter host/user device input settings.
AVD_CONFIG="$ANDROID_AVD_HOME/$AVD_NAME.avd/config.ini"
cp "$AVD_CONFIG" "$EVIDENCE/avd-config-original.ini"
python3 - "$AVD_CONFIG" <<'PYKEYBOARD'
import pathlib,re,sys
path=pathlib.Path(sys.argv[1]);text=path.read_text()
text=re.sub(r'^hw\.keyboard\s*=.*\n?', '', text, flags=re.M)
path.write_text(text.rstrip()+'\nhw.keyboard=no\n')
assert re.findall(r'^hw\.keyboard=(.*)$',path.read_text(),re.M)==['no']
PYKEYBOARD
# API36-only reduced software-rendered framebuffer; logical dp is unchanged.
python3 - "$AVD_CONFIG" "$API" <<'PYDISPLAYCONFIG'
import pathlib,re,sys
path=pathlib.Path(sys.argv[1]);api=int(sys.argv[2])
assert api in (26,30,36)
if api==36:
    text=path.read_text()
    for key,value in [('hw.lcd.width',720),('hw.lcd.height',1280),('hw.lcd.density',280)]:
        text=re.sub(r'^'+re.escape(key)+r'\s*=.*\n?', '', text, flags=re.M)
        text=text.rstrip()+f'\n{key}={value}\n'
    path.write_text(text)
PYDISPLAYCONFIG
cp "$AVD_CONFIG" "$EVIDENCE/avd-config-effective.ini"
"$SDK_ROOT/emulator/emulator" -list-avds | grep -Fx "$AVD_NAME"
adb start-server
# Refuse to touch any pre-existing device occupying our chosen emulator serial.
if adb devices | grep -q "^$SERIAL[[:space:]]"; then echo 'Emulator serial already occupied'; exit 1; fi
# Bounded hosted-runner sizing; retain the previous 2560 MB on smaller hosts.
# Renderer is the supported software backend, not deprecated swiftshader_indirect.
nproc > "$EVIDENCE/host-nproc.txt"
free -k > "$EVIDENCE/host-free.txt"
cat /proc/meminfo > "$EVIDENCE/host-meminfo.txt"
# Version metadata must not initialize the emulator GUI/Qt backend on a headless
# runner. Missing diagnostic metadata is explicit; the real launch still fails hard.
if [ -r "$SDK_ROOT/emulator/source.properties" ]; then
  cat "$SDK_ROOT/emulator/source.properties" > "$EVIDENCE/emulator-version.txt"
else
  printf 'UNAVAILABLE: installed emulator/source.properties not readable\n' > "$EVIDENCE/emulator-version.txt"
fi
read -r EMULATOR_CORES EMULATOR_MEMORY < <(python3 - "$EVIDENCE" <<'PYRESOURCES'
import json,pathlib,re,sys
root=pathlib.Path(sys.argv[1]);cpus=int((root/'host-nproc.txt').read_text())
assert cpus>0
available=int(re.search(r'^MemAvailable:\s+(\d+) kB$',(root/'host-meminfo.txt').read_text(),re.M)[1])
cores=min(4,cpus);memory=4096 if available>=6*1024*1024 else 2560
(root/'emulator-launch-options.json').write_text(json.dumps({'hostNproc':cpus,'hostMemAvailableKiB':available,
    'cores':cores,'memoryMiB':memory,'gpu':'swiftshader','disabledFeatures':['Vulkan'],'accel':'on','hardwareKeyboard':False,
    'scope':'Disposable test fixture; resource modernization is not proof of prior crash cause.'},indent=2)+'\n')
print(cores,memory)
PYRESOURCES
)
[[ "$EMULATOR_CORES" =~ ^[1-4]$ ]] && [[ "$EMULATOR_MEMORY" =~ ^(2560|4096)$ ]]
"$SDK_ROOT/emulator/emulator" -avd "$AVD_NAME" -port 5580 -no-window -no-audio -no-boot-anim -no-snapshot -wipe-data -gpu swiftshader -feature -Vulkan -cores "$EMULATOR_CORES" -memory "$EMULATOR_MEMORY" -accel on > "$EVIDENCE/emulator-runtime.log" 2>&1 &
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
adb -s "$SERIAL" logcat -v threadtime > "$EVIDENCE/continuous-system-logcat.txt" 2> "$EVIDENCE/continuous-system-logcat.stderr" &
HEALTH_LOG_PID=$!
# Event-based boot readiness: SystemUI service must exist in a live process,
# have finished its executing startup callbacks, and package/window must respond.
# Android ServiceRecord omits executeNesting when zero; nonzero means still busy.
SYSTEMUI_READY=false
SYSTEMUI_DEADLINE=$((SECONDS + 180))
sample=0
while [ "$SECONDS" -lt "$SYSTEMUI_DEADLINE" ]; do
  health_checkpoint "startup-$sample"
  timeout 15 adb -s "$SERIAL" shell dumpsys activity services com.android.systemui > "$EVIDENCE/systemui-startup-$sample.txt"
  if python3 - "$EVIDENCE/systemui-startup-$sample.txt" <<'PYSYSTEMUI'
import pathlib,re,sys
text=pathlib.Path(sys.argv[1]).read_text()
blocks=re.split(r'(?m)^\s*\* ServiceRecord\{',text)
service=next((b for b in blocks[1:] if re.search(r'com\.android\.systemui/(?:\.SystemUIService|com\.android\.systemui\.SystemUIService)(?=[}\s])',b.splitlines()[0])),None)
ready=bool(service and re.search(r'app=ProcessRecord\{[^}]*com\.android\.systemui',service)
    and 'startRequested=true' in service
    and not any(int(n)!=0 for n in re.findall(r'executeNesting=(\d+)',service))
    and 'destroying=true' not in service)
sys.exit(0 if ready else 1)
PYSYSTEMUI
  then
    if timeout 5 adb -s "$SERIAL" shell pm path android | grep -q '^package:' && \
       timeout 5 adb -s "$SERIAL" shell service check window | grep -q 'found'; then
      SYSTEMUI_READY=true
      cp "$EVIDENCE/systemui-startup-$sample.txt" "$EVIDENCE/systemui-ready.txt"
      health_checkpoint startup-ready
      break
    fi
  fi
  sample=$((sample + 1))
  sleep 2
done
[ "$SYSTEMUI_READY" = true ] || { echo 'SYSTEMUI_STARTUP_NOT_QUIESCENT'; exit 1; }
# Preserve boot diagnostics before installing either old or candidate APK. Never
# dismiss an ANR or treat a blocked system window as successful UI coverage.
timeout 20 adb -s "$SERIAL" logcat -d > "$EVIDENCE/pre-install-logcat.txt"
check_boot_health "$EVIDENCE/pre-install-logcat.txt" | tee "$EVIDENCE/pre-install-health.txt"
# Verify actual guest geometry, not only intended AVD configuration.
timeout 10 adb -s "$SERIAL" shell wm size > "$EVIDENCE/display-size.txt"
timeout 10 adb -s "$SERIAL" shell wm density > "$EVIDENCE/display-density.txt"
timeout 15 adb -s "$SERIAL" exec-out screencap -p > "$EVIDENCE/display-verification.png"
python3 - "$EVIDENCE" "$API" <<'PYDISPLAYVERIFY'
import pathlib,re,struct,sys,json
root=pathlib.Path(sys.argv[1]);api=int(sys.argv[2]);assert api in (26,30,36)
w,h,d=(720,1280,280) if api==36 else (1080,1920,420)
size=(root/'display-size.txt').read_text().strip()
density=(root/'display-density.txt').read_text().strip()
assert re.fullmatch(r'Physical size: '+str(w)+'x'+str(h),size),size
assert re.fullmatch(r'Physical density: '+str(d),density),density
png=(root/'display-verification.png').read_bytes()
assert png[:8]==b'\x89PNG\r\n\x1a\n' and png[12:16]==b'IHDR'
assert struct.unpack('>II',png[16:24])==(w,h)
(root/'display-verification.json').write_text(json.dumps({'api':api,'width':w,'height':h,'densityDpi':d,'logicalWidthDp':w*160/d,'logicalHeightDp':h*160/d,'verifiedGuestAndPng':True})+'\n')
PYDISPLAYVERIFY
configure_visible_software_keyboard
capture_input_diagnostics pre-install

timeout 10 adb -s "$SERIAL" shell pm list packages com.google.android.gms | tr -d '\r' > "$EVIDENCE/gms-packages.txt"
if grep -q '^package:com.google.android.gms$' "$EVIDENCE/gms-packages.txt"; then
  echo 'Unexpected GMS in AOSP default image'; exit 1
fi
printf 'Verified package manager ready; com.google.android.gms absent.\n' > "$EVIDENCE/aosp-verification.txt"

  retention_phase() {
    local mode="$1" method="$2" phase="$3"
    health_checkpoint "before-retention-$phase"
    set +e
    timeout 180 adb -s "$SERIAL" shell am instrument -w -r \
      -e class "com.example.scoreledger.UpgradeRetentionTest#$method" \
      -e upgradeRetention "$mode" -e allowPersistentSyntheticData true \
      -e upgradeNonce "$NONCE" "$RUNNER" 2>&1 | tee "$EVIDENCE/retention-$phase.txt"
    local status=${PIPESTATUS[0]}
    set -e
    [ "$status" = 0 ] || return 1
    health_checkpoint "after-retention-$phase"
    check_instrumentation "$EVIDENCE/retention-$phase.txt"
    # Require exactly one completed selected method. An assumption is NOT proof.
    python3 - "$EVIDENCE/retention-$phase.txt" "$method" <<'PYRETENTION'
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
    read_retention_marker "$phase"
  }
# Only this new AVD is modified. No uninstall, clear, or physical-device commands.
mapfile -d '' OLD_APPS < <(find signed-input/prior-app -type f -name '*.apk' -print0)
mapfile -d '' OLD_TESTS < <(find signed-input/prior-test -type f -name '*androidTest.apk' -print0)
[ "${#OLD_APPS[@]}" = 1 ] && [ "${#OLD_TESTS[@]}" = 1 ]
OLD_APP="${OLD_APPS[0]}"; OLD_TEST="${OLD_TESTS[0]}"
verify_apk "$OLD_APP" prior-app "$PACKAGE"
verify_apk "$OLD_TEST" prior-test "$TEST_PACKAGE"
python3 - "$EVIDENCE" "$OLD_APP" "$OLD_TEST" "$APP_APK" <<'PYUPGRADE'
import hashlib,json,os,pathlib,re,sys
root=pathlib.Path(sys.argv[1])
def sha(p): return hashlib.sha256(pathlib.Path(p).read_bytes()).hexdigest()
old_hash=sha(sys.argv[2]); new_hash=sha(sys.argv[4])
assert old_hash == os.environ['PRIOR_APP_SHA256'].lower(), 'Older APK hash mismatch'
assert old_hash != new_hash, 'Same APK reinstall is not upgrade'
def version(role):
 text=(root/(role+'-badging.txt')).read_text()
 return int(re.search(r"versionCode='(\d+)'",text)[1])
assert version('app') > version('prior-app'), 'Candidate versionCode must strictly increase'
sources={}
for role in ('app','test'):
 source=json.loads((root/('prior-'+role+'-source-run.json')).read_text())
 assert str(source['id'])==os.environ['PRIOR_'+role.upper()+'_RUN_ID']
 assert source['repository']==os.environ['GITHUB_REPOSITORY'] and source['conclusion']=='success'
 assert re.fullmatch('[0-9a-f]{40}',source['head_sha'])
 sources[role]=source
(root/'upgrade-provenance.json').write_text(json.dumps({'oldAppSha256':old_hash,'oldTestSha256':sha(sys.argv[3]),'newAppSha256':new_hash,'oldVersionCode':version('prior-app'),'newVersionCode':version('app'),'oldSources':sources},indent=2)+'\n')
PYUPGRADE
health_checkpoint before-prior-install
timeout 120 adb -s "$SERIAL" install "$OLD_APP" | tee "$EVIDENCE/prior-app-install.txt"
timeout 120 adb -s "$SERIAL" install "$OLD_TEST" | tee "$EVIDENCE/prior-test-install.txt"
NONCE=$(python3 -c 'import secrets; print(secrets.token_hex(24))')
retention_phase seed seedPersistentSyntheticData seed
timeout 120 adb -s "$SERIAL" install -r "$APP_APK" | tee "$EVIDENCE/app-install.txt"
timeout 120 adb -s "$SERIAL" install -r "$TEST_APK" | tee "$EVIDENCE/test-install.txt"
retention_phase verify verifyPersistentSyntheticData upgrade
cmp "$EVIDENCE/retention-seed-marker.json" "$EVIDENCE/retention-upgrade-marker.json"
cp "$EVIDENCE/retention-upgrade.txt" "$EVIDENCE/version-upgrade-verify.txt"
printf 'PASS: distinct older APK upgraded to candidate with strictly greater versionCode and identical fixed signer; complete canonical business snapshot and stored drafts matched.\n' > "$EVIDENCE/version-upgrade.txt"
# Independent shell process termination; ActivityScenario.recreate is not used.
adb -s "$SERIAL" shell am start -W -n "$PACKAGE/com.example.scoreledger.MainActivity" > "$EVIDENCE/pre-kill-launch.txt"
adb -s "$SERIAL" shell pidof "$PACKAGE" > "$EVIDENCE/pre-kill-pid.txt"
test -s "$EVIDENCE/pre-kill-pid.txt"
adb -s "$SERIAL" shell am force-stop "$PACKAGE"
if adb -s "$SERIAL" shell pidof "$PACKAGE"; then echo 'Process still alive'; exit 1; fi
retention_phase verify verifyPersistentSyntheticData process-death
cp "$EVIDENCE/retention-process-death.txt" "$EVIDENCE/external-process-death-verify.txt"
printf 'PASS: externally force-stopped process; persisted business snapshot and draft verified on restart. UI editor restoration and mid-I/O death NOT covered.\n' > "$EVIDENCE/process-death.txt"
# Separate opt-in real MainActivity draft journey. These methods must not enter
# the full-suite allowlist, where their phase assumptions would be skips.
external_draft_phase() {
  local phase="$1" method="$2"
  health_checkpoint "before-draft-$phase"
  timeout 180 adb -s "$SERIAL" shell am instrument -w -r \
    -e class "com.example.scoreledger.ExternalDraftResumeTest#$method" \
    -e externalDraftResume "$phase" -e allowPersistentSyntheticData true \
    -e upgradeNonce "$NONCE" "$RUNNER" 2>&1 | tee "$EVIDENCE/external-draft-$phase.txt"
  health_checkpoint "after-draft-$phase"
  python3 - "$EVIDENCE/external-draft-$phase.txt" "$method" <<'PYDRAFTPHASE'
import pathlib,sys
from tools.acceptance_gate import verify_methods
verify_methods(pathlib.Path(sys.argv[1]).read_text(), ['com.example.scoreledger.ExternalDraftResumeTest#'+sys.argv[2]])
PYDRAFTPHASE
}
external_draft_phase prepare prepareOwnedDraftThroughMainActivity
timeout 30 adb -s "$SERIAL" shell am start -W -n "$PACKAGE/com.example.scoreledger.MainActivity" > "$EVIDENCE/external-draft-pre-stop-launch.txt"
timeout 10 adb -s "$SERIAL" shell pidof "$PACKAGE" > "$EVIDENCE/external-draft-pre-stop-pid.txt"
grep -Eq '^[0-9]+([[:space:]]+[0-9]+)*[[:space:]]*$' "$EVIDENCE/external-draft-pre-stop-pid.txt"
timeout 10 adb -s "$SERIAL" shell am force-stop "$PACKAGE"
set +e
timeout 10 adb -s "$SERIAL" shell pidof "$PACKAGE" > "$EVIDENCE/external-draft-post-stop-pid.txt"
STOP_STATUS=$?
set -e
[ "$STOP_STATUS" = 1 ] && [ ! -s "$EVIDENCE/external-draft-post-stop-pid.txt" ] || { echo 'EXTERNAL_DRAFT_STOP_NOT_PROVEN'; exit 1; }
printf 'PASS: observed app PID, external am force-stop returned success, pidof returned 1 with no PID before resume instrumentation.\n' > "$EVIDENCE/external-draft-stop.txt"
external_draft_phase resume resumeOwnedDraftThroughMainActivityAfterExternalStop
timeout 15 adb -s "$SERIAL" shell run-as "$PACKAGE" cat "no_backup/external-draft-$NONCE.json" > "$EVIDENCE/external-draft-completed-marker.json"
python3 - "$EVIDENCE/external-draft-completed-marker.json" "$NONCE" <<'PYDRAFTDONE'
import json,pathlib,sys
marker=json.loads(pathlib.Path(sys.argv[1]).read_text())
assert marker['nonce']==sys.argv[2] and marker['phase']=='completed', 'External draft resume did not complete'
PYDRAFTDONE
adb -s "$SERIAL" shell pm list instrumentation | tr -d '\r' > "$EVIDENCE/instrumentation-target.txt"
grep -Fx "instrumentation:$RUNNER (target=$PACKAGE)" "$EVIDENCE/instrumentation-target.txt"
health_checkpoint before-full-suite
set +e
timeout 900 adb -s "$SERIAL" shell am instrument -w -r -e class "$(python3 tools/acceptance_gate.py --selection)" -e realMainJourney true -e allowPersistentSyntheticData true -e upgradeNonce "$NONCE" "$RUNNER" 2>&1 | tee "$EVIDENCE/full-instrumentation.txt"
INSTRUMENT_EXIT=${PIPESTATUS[0]}
set -e
health_checkpoint after-full-suite
[ "$INSTRUMENT_EXIT" = 0 ] || { echo "Instrumentation transport/timeout failure: $INSTRUMENT_EXIT"; exit 1; }
python3 tools/acceptance_gate.py --log "$EVIDENCE/full-instrumentation.txt"
timeout 30 adb -s "$SERIAL" shell am start -W -n "$PACKAGE/com.example.scoreledger.MainActivity" > "$EVIDENCE/launch.txt"
sleep 3
timeout 10 adb -s "$SERIAL" shell pidof "$PACKAGE" > "$EVIDENCE/app-pid.txt"
test -s "$EVIDENCE/app-pid.txt"
timeout 15 adb -s "$SERIAL" exec-out screencap -p > "$EVIDENCE/launch-screen.png"
test -s "$EVIDENCE/launch-screen.png"
if [ "${RUN_REINSTALL_RETENTION:-false}" = true ]; then
  # This opt-in only writes guarded synthetic data to the disposable emulator.
  # It deliberately fails rather than clearing any pre-existing database content.
  printf 'RUNNING: same-APK reinstall retention, nonce=%s\n' "$NONCE" > "$EVIDENCE/reinstall-retention.txt"

  timeout 120 adb -s "$SERIAL" install -r "$APP_APK" | tee "$EVIDENCE/reinstall.txt"
  retention_phase verify verifyPersistentSyntheticData reinstall
  cmp "$EVIDENCE/retention-seed-marker.json" "$EVIDENCE/retention-reinstall-marker.json"
  printf 'PASSED: same downloaded fixed-signed APK reinstalled with install -r; guarded synthetic data verified. NOT a version upgrade.\n' > "$EVIDENCE/reinstall-retention.txt"
fi
# Collection is mandatory on the success path, not a best-effort EXIT side effect.
collect_app_evidence pdf-evidence pdf-artifacts
collect_app_evidence ui-evidence ui-artifacts
python3 tools/acceptance_gate.py --log "$EVIDENCE/full-instrumentation.txt" --evidence "$EVIDENCE"
echo 'SIGNED_ARTIFACT_REQUIRED_METHODS_AND_EVIDENCE_PASSED; business-case coverage and visual review remain separate.' 
