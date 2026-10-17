import json
import os
import tempfile
import unittest
from datetime import datetime, timezone
from pathlib import Path
from unittest.mock import patch
from current_round_guard import ROUND, verify_round, verify_build_job, verify_candidate_version

class CurrentRoundGuardTest(unittest.TestCase):
    def setUp(self):
        self.env=patch.dict(os.environ, {'CURRENT_ROUND_DEVICE_ACCEPTANCE':ROUND,'GITHUB_REF':'refs/heads/main','GITHUB_EVENT_NAME':'push','GITHUB_RUN_ID':'123'})
        self.env.start(); self.addCleanup(self.env.stop)
    def test_exact_beta_version(self):
        verify_candidate_version("package: name='com.example.scoreledger.beta.debug' versionCode='2' versionName='0.2.0-beta' platformBuildVersionName='16'")
        for name in ('0.2.0', '0.2.1-beta', '0.1.0-beta'):
            with self.assertRaises(AssertionError): verify_candidate_version("package: name='com.example.scoreledger.beta.debug' versionCode='2' versionName='"+name+"'")
    def test_authorized_window(self): verify_round(datetime(2026,10,17,23,59,59,tzinfo=timezone.utc))
    def test_expiry(self):
        with self.assertRaises(AssertionError): verify_round(datetime(2026,10,18,tzinfo=timezone.utc))
    def test_untrusted_event(self):
        os.environ['GITHUB_EVENT_NAME']='pull_request'
        with self.assertRaises(AssertionError): verify_round(datetime(2026,10,17,tzinfo=timezone.utc))
    def test_round_disabled(self):
        os.environ['CURRENT_ROUND_DEVICE_ACCEPTANCE']=''
        with self.assertRaises(AssertionError): verify_round(datetime(2026,10,17,tzinfo=timezone.utc))
    def test_build_identity_and_success_required(self):
        with tempfile.TemporaryDirectory() as directory, patch('current_round_guard.verify_round'):
            job={'name':'internal-apk','run_id':123,'head_sha':'a'*40,'status':'completed','conclusion':'success'}
            path=Path(directory)/'current-build-jobs.json'
            path.write_text(json.dumps([{'jobs':[job]}]))
            self.assertEqual(job,verify_build_job(directory,'123','a'*40))
            for field,value in [('head_sha','b'*40),('conclusion','failure'),('status','in_progress'),('run_id',124)]:
                path.write_text(json.dumps([{'jobs':[dict(job,**{field:value})]}]))
                with self.assertRaises(AssertionError):verify_build_job(directory,'123','a'*40)
            path.write_text(json.dumps([{'jobs':[job,job]}]))
            with self.assertRaises(AssertionError):verify_build_job(directory,'123','a'*40)
if __name__=='__main__':unittest.main()
