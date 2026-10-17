#!/usr/bin/env python3
"""Fail-closed signed-device gate. A method pass is not a business-case pass."""
import argparse
import hashlib
import json
import pathlib
import re


def verify_methods(text, required):
    text = text.replace('\r', '')
    assert required and len(required) == len(set(required)), 'Empty/duplicate required methods'
    assert re.findall(r'^INSTRUMENTATION_CODE:\s*(-?\d+)\s*$', text, re.M) == ['-1'], 'Incomplete runner'
    assert not re.search(r'FAILURES!!!|INSTRUMENTATION_FAILED|INSTRUMENTATION_ABORTED|Process crashed|shortMsg=', text), 'Runner failed'
    counts = re.findall(r'^OK \((\d+) tests?\)\s*$', text, re.M)
    assert counts == [str(len(required))], 'Wrong final test count'
    blocks = re.split(r'^INSTRUMENTATION_STATUS_CODE:\s*(-?\d+)\s*$', text, flags=re.M)
    started, completed = [], []
    for index in range(1, len(blocks), 2):
        status, block = blocks[index], blocks[index - 1]
        assert status in ('0', '1'), 'Skipped/assumed/failed method'
        classes = re.findall(r'^INSTRUMENTATION_STATUS: class=(.+)$', block, re.M)
        methods = re.findall(r'^INSTRUMENTATION_STATUS: test=(.+)$', block, re.M)
        assert len(classes) == len(methods) == 1, 'Missing method identity'
        (started if status == '1' else completed).append(classes[0] + '#' + methods[0])
    assert sorted(started) == sorted(required), 'Required starts missing/duplicated/unexpected'
    assert sorted(completed) == sorted(required), 'Required completions missing/duplicated/unexpected'
    return completed


def verify_files(root, required):
    results = []
    for name in required:
        path = root / name
        assert not path.is_symlink() and path.is_file(), 'Missing evidence: ' + name
        raw = path.read_bytes()
        if name.endswith('.png'):
            assert len(raw) > 100 and raw[:8] == b'\x89PNG\r\n\x1a\n' and b'IEND' in raw[-20:], 'Invalid PNG: ' + name
        elif name.endswith('.pdf'):
            assert len(raw) > 100 and raw.startswith(b'%PDF-') and b'%%EOF' in raw[-1024:], 'Invalid PDF: ' + name
        else:
            assert raw, 'Empty evidence: ' + name
        results.append({'path': name, 'bytes': len(raw), 'sha256': hashlib.sha256(raw).hexdigest()})
    return results


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--manifest', default='tools/required_device_acceptance.json')
    parser.add_argument('--log')
    parser.add_argument('--evidence')
    parser.add_argument('--selection', action='store_true')
    args = parser.parse_args()
    manifest = json.loads(pathlib.Path(args.manifest).read_text())
    if args.selection:
        print(','.join(manifest['requiredMethods']))
        return
    completed = verify_methods(pathlib.Path(args.log).read_text(errors='replace'), manifest['requiredMethods'])
    output = {'status': 'METHODS_VERIFIED', 'completedMethods': completed, 'businessCaseStatus': 'SEPARATE_REVIEW_REQUIRED'}
    if args.evidence:
        root = pathlib.Path(args.evidence)
        output['files'] = verify_files(root, manifest['requiredFiles'])
        output['separatePhaseMethods'] = {
            phase['log']: verify_methods((root / phase['log']).read_text(errors='replace'), [phase['method']])
            for phase in manifest.get('requiredPhaseLogs', [])
        }
        output['status'] = 'METHODS_AND_REQUIRED_EVIDENCE_VERIFIED'
        (root / 'evidence-gate.json').write_text(json.dumps(output, indent=2) + '\n')
    print(json.dumps(output, indent=2))

if __name__ == '__main__':
    main()
