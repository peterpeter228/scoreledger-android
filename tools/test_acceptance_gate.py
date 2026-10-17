import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path
from acceptance_gate import verify_methods, verify_files

METHOD = 'example.Test#required'
def log():
    return ('INSTRUMENTATION_STATUS: class=example.Test\nINSTRUMENTATION_STATUS: test=required\nINSTRUMENTATION_STATUS_CODE: 1\n'
            'INSTRUMENTATION_STATUS: class=example.Test\nINSTRUMENTATION_STATUS: test=required\nINSTRUMENTATION_STATUS_CODE: 0\n'
            'OK (1 test)\nINSTRUMENTATION_CODE: -1\n')
class GateTest(unittest.TestCase):
    def test_success(self): self.assertEqual([METHOD], verify_methods(log(), [METHOD]))
    def test_missing_method(self):
        with self.assertRaises(AssertionError): verify_methods(log(), ['example.Test#other'])
    def test_skip(self):
        with self.assertRaises(AssertionError): verify_methods(log().replace('CODE: 0','CODE: -3'), [METHOD])
    def test_truncation(self):
        with self.assertRaises(AssertionError): verify_methods(log().split('OK')[0], [METHOD])
    def test_duplicate(self):
        with self.assertRaises(AssertionError): verify_methods(log()+log(), [METHOD])
    def test_separate_phase_is_mandatory_and_exact(self):
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory)
            (root/'full.txt').write_text(log())
            (root/'manifest.json').write_text(json.dumps({'requiredMethods':[METHOD], 'requiredFiles':[],
                'requiredPhaseLogs':[{'log':'phase.txt','method':METHOD}]}))
            command=[sys.executable,str(Path(__file__).with_name('acceptance_gate.py')),
                '--manifest',str(root/'manifest.json'),'--log',str(root/'full.txt'),'--evidence',str(root)]
            self.assertNotEqual(subprocess.run(command,capture_output=True).returncode,0)
            for payload in (log().replace('CODE: 0','CODE: -3'),log().replace('required','other')):
                (root/'phase.txt').write_text(payload)
                self.assertNotEqual(subprocess.run(command,capture_output=True).returncode,0)
            (root/'phase.txt').write_text(log())
            self.assertEqual(subprocess.run(command,capture_output=True).returncode,0)
            self.assertEqual(json.loads((root/'evidence-gate.json').read_text())['separatePhaseMethods'],{'phase.txt':[METHOD]})

    def test_absent_and_corrupt_evidence(self):
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory)
            with self.assertRaises(AssertionError): verify_files(root,['required.pdf'])
            (root/'required.pdf').write_bytes(b'not pdf'*100)
            with self.assertRaises(AssertionError): verify_files(root,['required.pdf'])
if __name__=='__main__': unittest.main()
