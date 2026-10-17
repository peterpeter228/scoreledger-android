#!/usr/bin/env python3
"""Fail-closed commercial release checklist. Evidence does not replace user approval."""
import json, re, sys
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]
def main():
    path=ROOT/'release/channel_config.json';errors=[]
    try:c=json.loads(path.read_text(encoding='utf-8'))
    except (OSError,ValueError) as e:print(f'BLOCKED: invalid configuration ({type(e).__name__})');return 1
    for name in ['applicationId','appName','developerName','supportEmail','privacyUrl','channel','productId']:
        v=c.get(name)
        if not isinstance(v,str) or not v.strip() or 'TODO' in v or 'example' in v or '工作名' in v:errors.append(f'{name}: real release configuration required')
    if c.get('channel') not in ['play','domestic']:errors.append('channel: approved commercial channel required')
    if not re.fullmatch(r'[^@\s]+@[^@\s]+\.[^@\s]+',c.get('supportEmail','')):errors.append('supportEmail: invalid')
    if not str(c.get('privacyUrl','')).startswith('https://'):errors.append('privacyUrl: public HTTPS URL required')
    for flag in ['signingCustodyConfirmed','userApprovedPublicRelease']:
        if c.get(flag) is not True:errors.append(f'{flag}: explicit confirmation missing')
    for name in ['billingEvidence','deviceEvidence','legalReviewEvidence']:
        ref=c.get(name,'');p=(ROOT/str(ref)).resolve()
        if ROOT not in p.parents or p.suffix!='.json' or not p.is_file():errors.append(f'{name}: missing evidence JSON');continue
        try:
            record=json.loads(p.read_text(encoding='utf-8'))
            if record.get('status')!='PASS' or not record.get('verifiedAt') or not record.get('checkedBy') or not record.get('artifacts'):
                errors.append(f'{name}: incomplete or not PASS')
            for artifact in record.get('artifacts',[]):
                f=(ROOT/str(artifact)).resolve()
                if ROOT not in f.parents or not f.is_file() or f.stat().st_size==0:errors.append(f'{name}: missing referenced artifact')
        except (OSError,ValueError,TypeError):errors.append(f'{name}: unreadable evidence')
    if errors:
        print('COMMERCIAL_RELEASE_BLOCKED\n'+'\n'.join('- '+e for e in errors));return 1
    print('TECHNICAL_CHECKLIST_PASS: requires independent review, channel policy compliance, secure signing and valid user authorization.');return 0
if __name__=='__main__':sys.exit(main())
