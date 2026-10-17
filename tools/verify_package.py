#!/usr/bin/env python3
"""Static handoff checks, NOT Android compilation or a security certification."""
import json, re, sys, xml.etree.ElementTree as ET
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]
sys.path.insert(0,str(ROOT/'reference'))
from backup_validator import loads
REQUIRED=['AGENTS.md','START_HERE.md','DOT_START_PROMPT.txt','PROJECT_STATE.json','docs/PRD.md',
'docs/IMPLEMENTATION_STATUS.md','tasks/BACKLOG.json','tasks/ACCEPTANCE.csv','docs/DATA_CONTRACT.md',
'docs/STATISTICS_RULES.md','docs/BACKUP_RESTORE.md','docs/UI_UX.md','contracts/backup-v1.schema.json',
'.github/workflows/android-ci.yml','gradlew','gradlew.bat','app/src/main/AndroidManifest.xml','tools/release_guard.py']
def main():
    errors=[]
    for path in REQUIRED:
        if not (ROOT/path).is_file():errors.append('Missing '+path)
    for p in ROOT.rglob('*.json'):
        if any(x in p.relative_to(ROOT).parts for x in ('.gradle', '.gradle-local', '.toolchains', 'build', 'out')):continue
        try:json.loads(p.read_text(encoding='utf-8'))
        except (OSError,ValueError) as e:errors.append(f'JSON {p.relative_to(ROOT)}: {e}')
    for p in (ROOT/'app/src').rglob('*.xml'):
        try:ET.parse(p)
        except ET.ParseError as e:errors.append(f'XML {p.relative_to(ROOT)}: {e}')
    try:
        manifest=ET.parse(ROOT/'app/src/main/AndroidManifest.xml').getroot()
        if manifest.findall('uses-permission'):errors.append('Unexpected permission in main offline manifest; review flavor separation')
        for fixture in ['backup-valid.json','backup-empty.json']:loads((ROOT/'fixtures'/fixture).read_bytes())
        backlog=json.loads((ROOT/'tasks/BACKLOG.json').read_text(encoding='utf-8'))['tasks'];tasks={t['id']:t for t in backlog}
        if len(tasks)!=len(backlog):errors.append('Duplicate task id')
        visited=set();visiting=set()
        def visit(k):
            if k in visiting:raise ValueError('Dependency cycle: '+k)
            if k in visited:return
            if k not in tasks:raise ValueError('Missing dependency: '+k)
            visiting.add(k)
            for d in tasks[k]['dependsOn']:visit(d)
            visiting.remove(k);visited.add(k)
        for k in tasks:visit(k)
    except (OSError,ValueError,KeyError,TypeError) as e:errors.append(str(e))
    for p in ROOT.rglob('*'):
        if any(x in p.relative_to(ROOT).parts for x in ('.gradle', '.gradle-local', '.toolchains', 'build', 'out')):continue
        if p.is_file() and p.suffix.lower() in ['.jks','.keystore','.pem','.p12']:errors.append('Unexpected signing/secret file '+str(p.relative_to(ROOT)))
    if errors:print('STATIC_CHECK_FAIL\n'+'\n'.join(errors));return 1
    print('STATIC_CHECK_PASS: required files, JSON/XML, backup fixtures, dependency DAG, offline manifest, no signing files. Android compilation NOT verified.');return 0
if __name__=='__main__':sys.exit(main())
