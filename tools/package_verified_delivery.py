"""Package a pinned, already accepted beta. No model keys, signing keys, or new tests."""
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess

ROOT = Path.cwd()
INPUT = ROOT / 'verified-input'
OUT = ROOT / 'delivery-staging'
ACCEPTED = 'f673e787500d8754463b919b91e236eae643eb97'
APK_SHA = '2f39a2daaa08ee34d29ab5030309fac57ed2e529406c1233c7877521cc5d2f02'
TEST_SHA = 'e24ba3c6ee55d31cfc51830a6c2eca3b6905b2ea9fb843f6bde9af038620aad9'

def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()

assert not OUT.exists(), 'Refuse to overwrite a previous delivery directory'
OUT.mkdir()
apks = list((INPUT / 'apk').glob('*.apk'))
assert len(apks) == 1 and sha(apks[0]) == APK_SHA
metadata = json.loads((INPUT / 'apk' / 'app-localBeta-debug.metadata.json').read_text())
assert metadata['certificateSha256'] == 'bc67f6b0ccff15581b5c08ca90df4d877c4067e3ed33bd183caa071b93954e1c'
shutil.copyfile(apks[0], OUT / 'ScoreLedger-0.2.0-beta.apk')
source = OUT / 'scoreledger-v2-work'
tracked = subprocess.check_output(['git', 'ls-files', '-z']).decode().split('\0')
for name in filter(None, tracked):
    src = ROOT / name
    assert src.is_file() and not src.is_symlink()
    dst = source / name
    dst.parent.mkdir(parents=True, exist_ok=True)
    shutil.copy2(src, dst)
# Packaging/documentation commits must not change any accepted app/core/runtime source.
changed = subprocess.check_output(['git', 'diff', '--name-only', ACCEPTED, 'HEAD']).decode().splitlines()
assert not any(n.startswith(('app/', 'core/')) or n in ('build.gradle.kts', 'settings.gradle.kts', 'gradle.properties') for n in changed), changed
manifest = json.loads((ROOT / 'tools/required_device_acceptance.json').read_text())
required = set(manifest['requiredMethods'])
assert len(required) == 65
for api in (26, 30, 36):
    src = INPUT / 'devices' / f'current-round-signed-device-api-{api}-attempt-1'
    provenance = json.loads((src / 'artifact-provenance.json').read_text())
    assert provenance['acceptanceSourceSha'] == ACCEPTED
    assert provenance['acceptanceRunId'] == '39036648565'
    assert provenance['expectedAppSha256'] == APK_SHA
    assert {a['role']: a['sha256'] for a in provenance['apks']} == {'app': APK_SHA, 'instrumentation': TEST_SHA}
    assert (src / 'result.txt').read_text().strip() == 'acceptance_exit_code=0'
    gate = json.loads((src / 'evidence-gate.json').read_text())
    assert set(gate['completedMethods']) == required and len(gate['completedMethods']) == 65
    assert sum(map(len, gate['separatePhaseMethods'].values())) == 2
    for item in gate['files']:
        path = src / item['path']
        assert path.is_file() and path.stat().st_size == item['bytes'] and sha(path) == item['sha256']
    shutil.copytree(src, OUT / 'scoreledger-revision-evidence' / f'api{api}-eighteenth')
shutil.copytree(INPUT / 'build-reports', OUT / 'scoreledger-revision-evidence/build-reports-eighteenth')
# Keep signing report and metadata but avoid a duplicate business APK.
apk_evidence = OUT / 'scoreledger-revision-evidence/apk-eighteenth'
apk_evidence.mkdir()
for path in (INPUT / 'apk').iterdir():
    if path.is_file() and path.suffix != '.apk':
        shutil.copy2(path, apk_evidence / path.name)
readme = (ROOT / 'docs/DELIVERY_0.2.0.md').read_text()
readme += '\n本整包为 GitHub Actions 的单一 ZIP 产物。APK、源码、三个 API 的完整本轮原始证据均在包内，无需二次压缩包解压。旧历史证据不作为本次验收替代；历史引用可在对应旧工作流查阅。\n'
readme += '\n交付打包源码提交：' + os.environ['GITHUB_SHA'] + '\n'
(OUT / '先读我.txt').write_text(readme)
files = {}
for path in OUT.rglob('*'):
    if path.is_file():
        files[path.relative_to(OUT).as_posix()] = {'bytes': path.stat().st_size, 'sha256': sha(path)}
(OUT / '文件校验清单.json').write_text(json.dumps({'acceptedSource': ACCEPTED, 'acceptedRun': 39036648565, 'appSha256': APK_SHA, 'files': files}, ensure_ascii=False, indent=2))
print('Verified delivery ready:', len(files), 'files; app', APK_SHA)
