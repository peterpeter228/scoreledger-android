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
