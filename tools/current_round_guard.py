#!/usr/bin/env python3
"""Bounded authorization guard for the explicitly approved 0.2.0 device-test round."""
from datetime import datetime, timezone
import json
import os
import pathlib
import sys

ROUND = 'scoreledger-0.2.0-2026-10-17'
EXPIRES = datetime(2026, 10, 18, tzinfo=timezone.utc)
EXPECTED_VERSION_NAME = '0.2.0-beta'

def verify_candidate_version(badging):
    import re
    versions = re.findall(r"^package: .*? versionName='([^']+)'", badging, re.M)
    assert versions == [EXPECTED_VERSION_NAME], 'Wrong current-round candidate versionName'

def verify_round(now=None):
    assert os.environ.get('CURRENT_ROUND_DEVICE_ACCEPTANCE') == ROUND, 'Current-round enable tag missing'
    assert (now or datetime.now(timezone.utc)) < EXPIRES, 'Current-round KVM authorization expired'
    assert os.environ.get('GITHUB_REF') == 'refs/heads/main', 'Trusted main only'
    assert os.environ.get('GITHUB_EVENT_NAME') in ('push', 'workflow_dispatch'), 'Trusted build event only'

def verify_build_job(root, run_id, source_sha):
    verify_round()
    assert str(run_id) == os.environ['GITHUB_RUN_ID'], 'Only current workflow may use successful-build-job proof'
    pages = json.loads((pathlib.Path(root) / 'current-build-jobs.json').read_text())
    jobs = [job for page in pages for job in page['jobs']]
    build = [job for job in jobs if job['name'] == 'internal-apk']
    assert len(build) == 1, 'Missing or ambiguous prerequisite build job'
    job = build[0]
    assert str(job['run_id']) == str(run_id) and job['head_sha'] == source_sha, 'Build identity mismatch'
    assert job['status'] == 'completed' and job['conclusion'] == 'success', 'Prerequisite build did not pass'
    return job

if __name__ == '__main__':
    verify_round()
    if len(sys.argv) > 1:
        verify_candidate_version(pathlib.Path(sys.argv[1]).read_text())
    print('Current ScoreLedger 0.2.0 round authorized until 2026-10-17 23:59:59 UTC; ephemeral KVM only.')
