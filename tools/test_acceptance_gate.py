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
    def test_absent_and_corrupt_evidence(self):
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory)
            with self.assertRaises(AssertionError): verify_files(root,['required.pdf'])
            (root/'required.pdf').write_bytes(b'not pdf'*100)
            with self.assertRaises(AssertionError): verify_files(root,['required.pdf'])
if __name__=='__main__': unittest.main()
