"""Host-only regression of the exact collector with a simulated adb transport.

This checks shell quoting and archive safety, not Android run-as availability.
Actual API 26 evidence remains mandatory in acceptance_gate.py.
"""
import os
from pathlib import Path
import subprocess
import tempfile
import unittest

SCRIPT = Path(__file__).with_name('signed_emulator_acceptance.sh').read_text()
COLLECTOR = SCRIPT[SCRIPT.index('collect_app_evidence() {'):SCRIPT.index('\ncollect_and_stop() {')]
ADB = '''#!/usr/bin/env python3
import os, shlex, subprocess, sys
args = sys.argv[1:]
assert args[:2] == ['-s', 'emulator-5580']
args = args[2:]
if args[0] == 'shell':
    assert len(args) == 2, 'Remote shell command must preserve quoting'
    command = shlex.split(args[1])
    assert command[:4] == ['run-as', 'com.example.scoreledger.beta.debug', 'sh', '-c']
    assert len(command) == 5
    assert command[4].startswith('test -d "') and '&& ! test -L "' in command[4]
    sys.exit(subprocess.call(command[2:]))
assert args[:3] == ['exec-out', 'run-as', 'com.example.scoreledger.beta.debug']
sys.exit(subprocess.call(args[3:]))
'''

class EvidenceCollectionTests(unittest.TestCase):
    def run_collection(self, mode):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            (root / 'bin').mkdir()
            adb = root / 'bin/adb'
            adb.write_text(ADB)
            adb.chmod(0o755)
            evidence = root / 'evidence'
            evidence.mkdir()
            source = root / 'files/ui-evidence'
            source.mkdir(parents=True)
            if mode == 'regular':
                (source / 'synthetic.png').write_bytes(b'synthetic fixture')
            elif mode == 'member-symlink':
                (source / 'link').symlink_to('../outside')
            elif mode == 'directory-symlink':
                source.rmdir()
                source.symlink_to(evidence, target_is_directory=True)
            elif mode == 'missing':
                source.rmdir()
            command = COLLECTOR + '\ncollect_app_evidence ui-evidence ui-artifacts\n'
            result = subprocess.run(['bash', '-c', command], cwd=root, env={
                **os.environ, 'PATH': str(root / 'bin') + ':' + os.environ['PATH'],
                'EVIDENCE': str(evidence), 'SERIAL': 'emulator-5580',
                'PACKAGE': 'com.example.scoreledger.beta.debug'}, capture_output=True, text=True)
            output = evidence / 'ui-artifacts/synthetic.png'
            return result, output.read_bytes() if output.exists() else None

    def test_systemui_readiness_requires_live_started_quiescent_service(self):
        code=SCRIPT.split("<<'PYSYSTEMUI'\n",1)[1].split('\nPYSYSTEMUI',1)[0]
        ready='ACTIVITY MANAGER SERVICES\n  * ServiceRecord{abc u0 com.android.systemui/.SystemUIService}\n    app=ProcessRecord{abc 123:com.android.systemui/u0a1}\n    startRequested=true\n'
        with tempfile.TemporaryDirectory() as tmp:
            path=Path(tmp)/'services.txt'
            for text,expected in [(ready,0),(ready+'    executeNesting=1 executingStart=-10s\n',1),
                (ready.replace('app=ProcessRecord{abc 123:com.android.systemui/u0a1}','app=null'),1),
                (ready.replace('startRequested=true','startRequested=false'),1),('',1),
                (ready.replace('.SystemUIService}', '.OtherService}'),1)]:
                path.write_text(text)
                result=subprocess.run(['python3','-',str(path)],input=code,text=True,capture_output=True)
                self.assertEqual(result.returncode,expected,result.stderr)

    def test_version_diagnostic_reads_metadata_without_starting_emulator(self):
        self.assertNotIn('"$SDK_ROOT/emulator/emulator" -version',SCRIPT)
        start=SCRIPT.index('if [ -r "$SDK_ROOT/emulator/source.properties" ]; then')
        code=SCRIPT[start:SCRIPT.index('\nfi',start)+3]
        with tempfile.TemporaryDirectory() as tmp:
            root=Path(tmp);(root/'emulator').mkdir();(root/'evidence').mkdir()
            env={**os.environ,'SDK_ROOT':str(root),'EVIDENCE':str(root/'evidence')}
            result=subprocess.run(['bash','-e','-c',code],env=env,capture_output=True)
            self.assertEqual(result.returncode,0)
            self.assertIn('UNAVAILABLE',(root/'evidence/emulator-version.txt').read_text())
            (root/'emulator/source.properties').write_text('Pkg.Revision=37.2.12\n')
            result=subprocess.run(['bash','-e','-c',code],env=env,capture_output=True)
            self.assertEqual(result.returncode,0)
            self.assertEqual((root/'evidence/emulator-version.txt').read_text(),'Pkg.Revision=37.2.12\n')

    def test_resource_selection_is_bounded_by_actual_host(self):
        code=SCRIPT.split("<<'PYRESOURCES'\n",1)[1].split('\nPYRESOURCES',1)[0]
        with tempfile.TemporaryDirectory() as tmp:
            root=Path(tmp)
            for cpus,available,expected in [(2,5*1024*1024,'2 2560'),(8,6*1024*1024,'4 4096'),(1,9*1024*1024,'1 4096')]:
                (root/'host-nproc.txt').write_text(str(cpus))
                (root/'host-meminfo.txt').write_text(f'MemAvailable: {available} kB\n')
                result=subprocess.run(['python3','-',str(root)],input=code,text=True,capture_output=True)
                self.assertEqual(result.returncode,0,result.stderr)
                self.assertEqual(result.stdout.strip(),expected)

    def test_visible_software_keyboard_preference_is_scoped_and_verified(self):
        code=SCRIPT.split('configure_visible_software_keyboard() {',1)[1].split('\n}\n',1)[0]
        with tempfile.TemporaryDirectory() as tmp:
            root=Path(tmp)
            for actual,passes in [('1',True),('0',False),('null',False)]:
                shell = 'set -e\nSERIAL=emulator-5580\nEVIDENCE="'+str(root)+'"\n' + r'''timeout() { shift; "$@"; }
adb() {
  [ "$1" = -s ] && [ "$2" = emulator-5580 ] || return 9
  shift 2
  case "$*" in
    "shell settings get secure show_ime_with_hard_keyboard") echo "$READBACK" ;;
    "shell settings put secure show_ime_with_hard_keyboard 1") echo "$*" >> "$EVIDENCE/writes.txt" ;;
    *) return 9 ;;
  esac
}
''' + code
                result=subprocess.run(['bash','-c',shell],env={**os.environ,'READBACK':actual},capture_output=True,text=True)
                self.assertEqual(result.returncode==0,passes,result.stdout+result.stderr)
                self.assertEqual((root/'software-keyboard-preference-before.txt').read_text().strip(),actual)
                self.assertEqual((root/'software-keyboard-preference-after.txt').read_text().strip(),actual)
            self.assertEqual((root/'writes.txt').read_text().splitlines(),['shell settings put secure show_ime_with_hard_keyboard 1']*3)

    def test_guest_framebuffer_verification_rejects_mismatch(self):
        import struct
        code=SCRIPT.split("<<'PYDISPLAYVERIFY'\n",1)[1].split('\nPYDISPLAYVERIFY',1)[0]
        with tempfile.TemporaryDirectory() as tmp:
            root=Path(tmp)
            for api,w,h,d,good in [(36,720,1280,280,True),(26,1080,1920,420,True),(30,1080,1920,420,True),(36,1080,1920,420,False)]:
                (root/'display-size.txt').write_text(f'Physical size: {w}x{h}\n')
                (root/'display-density.txt').write_text(f'Physical density: {d}\n')
                (root/'display-verification.png').write_bytes(b'\x89PNG\r\n\x1a\n'+struct.pack('>I',13)+b'IHDR'+struct.pack('>II',w,h))
                result=subprocess.run(['python3','-',str(root),str(api)],input=code,text=True,capture_output=True)
                self.assertEqual(result.returncode==0,good,result.stderr)
            (root/'display-size.txt').write_text('Physical size: 720x1280\n')
            (root/'display-density.txt').write_text('Physical density: 280\n')
            result=subprocess.run(['python3','-',str(root),'36'],input=code,text=True,capture_output=True)
            self.assertNotEqual(result.returncode,0,'Wrong PNG dimensions must fail')

    def test_retention_marker_read_retry_is_guarded_and_atomic(self):
        import json
        function='read_retention_marker() {'+SCRIPT.split('read_retention_marker() {',1)[1].split('\ncapture_input_diagnostics()',1)[0]
        nonce='a'*48
        payload=json.dumps(dict(nonce=nonce,childId='child',examId='exam',draftId='draft'))
        with tempfile.TemporaryDirectory() as tmp:
            root=Path(tmp)
            for case,success,reads in [('transient',True,2),('denied',False,1),('unhealthy',False,1),('always255',False,2),('wrongnonce',False,1)]:
                folder=root/case;folder.mkdir();(folder/'retention-seed-marker.json').write_text(payload)
                shell='set -e\nSERIAL=emulator-5580\nPACKAGE=com.example.scoreledger.beta.debug\n'+function+r'''
timeout() { shift; "$@"; }
health_checkpoint() { [ "$CASE" != unhealthy ]; }
adb() {
  [ "$1" = -s ] && [ "$2" = emulator-5580 ] || return 9
  shift 2
  case "$*" in
    "get-state") echo device ;;
    "shell pm path $PACKAGE") echo package:/synthetic.apk ;;
    "shell run-as $PACKAGE cat no_backup/upgrade-retention-$NONCE.json")
      echo read >> "$EVIDENCE/read-count"
      if [ "$CASE" = denied ]; then echo 'Permission denied' >&2; return 255; fi
      if [ "$CASE" = wrongnonce ]; then echo '{}'; return 0; fi
      if [ "$CASE" = always255 ] || [ "$(wc -l < "$EVIDENCE/read-count")" = 1 ]; then return 255; fi
      cat "$EVIDENCE/retention-seed-marker.json" ;;
    *) return 9 ;;
  esac
}
read_retention_marker upgrade
'''
                result=subprocess.run(['bash','-c',shell],env={**os.environ,'CASE':case,'NONCE':nonce,'EVIDENCE':str(folder)},capture_output=True,text=True)
                self.assertEqual(result.returncode==0,success,result.stderr)
                self.assertEqual(len((folder/'read-count').read_text().splitlines()),reads)
                self.assertEqual((folder/'retention-upgrade-marker.json').exists(),success)
                self.assertEqual((folder/'retention-seed-marker.json').read_text(),payload)
        for phase in ('upgrade','process-death','reinstall'):
            self.assertIn('retention_phase verify verifyPersistentSyntheticData '+phase,SCRIPT)
        self.assertNotIn('retention-verify.txt',SCRIPT)

    def test_framebuffer_transform_is_api36_only(self):
        code=SCRIPT.split("<<'PYDISPLAYCONFIG'\n",1)[1].split('\nPYDISPLAYCONFIG',1)[0]
        original='hw.lcd.width=1080\nhw.lcd.height=1920\nhw.lcd.density=420\nhw.keyboard=no\n'
        with tempfile.TemporaryDirectory() as tmp:
            path=Path(tmp)/'config.ini'
            for api in (26,30,36,35):
                path.write_text(original)
                result=subprocess.run(['python3','-',str(path),str(api)],input=code,text=True,capture_output=True)
                self.assertEqual(result.returncode==0,api!=35)
                expected='hw.keyboard=no\nhw.lcd.width=720\nhw.lcd.height=1280\nhw.lcd.density=280\n' if api==36 else original
                self.assertEqual(path.read_text(),expected)

    def test_touch_only_fixture_replaces_only_keyboard_setting(self):
        code=SCRIPT.split("<<'PYKEYBOARD'\n",1)[1].split('\nPYKEYBOARD',1)[0]
        with tempfile.TemporaryDirectory() as tmp:
            path=Path(tmp)/'config.ini'
            for original in ('hw.keyboard=yes\nhw.ramSize=2560\n','hw.ramSize=2560\n'):
                path.write_text(original)
                result=subprocess.run(['python3','-',str(path)],input=code,text=True,capture_output=True)
                self.assertEqual(result.returncode,0,result.stderr)
                self.assertEqual(path.read_text(),'hw.ramSize=2560\nhw.keyboard=no\n')

    def test_boot_health_rejects_system_anr_and_empty_logs(self):
        script = Path(__file__).with_name('signed_emulator_acceptance.sh').resolve()
        cases = [('I SystemServer: Boot complete', True),
                 ('E ActivityManager: ANR in com.android.systemui\n', False),
                 ('E ActivityManager: ANR in com.android.phone\n', False),
                 ('E AndroidRuntime: *** FATAL EXCEPTION IN SYSTEM PROCESS: WifiHandlerThread', False),
                 ('', False)]
        with tempfile.TemporaryDirectory() as tmp:
            log = Path(tmp) / 'boot.log'
            for text, passes in cases:
                with self.subTest(text=text):
                    log.write_text(text)
                    result = subprocess.run(['bash', str(script), '--check-boot-health', str(log)], capture_output=True)
                    self.assertEqual(result.returncode == 0, passes)

    def test_builtin_probe_and_regular_archive(self):
        result, data = self.run_collection('regular')
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertEqual(data, b'synthetic fixture')

    def test_missing_evidence_is_failure(self):
        result, _ = self.run_collection('missing')
        self.assertNotEqual(result.returncode, 0)
        self.assertIn('EVIDENCE_NOT_PRESENT', result.stdout)

    def test_symlink_directory_is_rejected(self):
        result, _ = self.run_collection('directory-symlink')
        self.assertNotEqual(result.returncode, 0)

    def test_symlink_archive_member_is_rejected(self):
        result, _ = self.run_collection('member-symlink')
        self.assertNotEqual(result.returncode, 0)
        self.assertIn('Unsafe evidence archive member', result.stderr)

if __name__ == '__main__':
    unittest.main()
