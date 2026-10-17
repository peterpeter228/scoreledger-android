#!/usr/bin/env python3
"""Verify transport integrity of the unmodified handoff. Not a digital signature."""
import hashlib,json,sys
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]
def main():
    manifest=json.loads((ROOT/'manifest.json').read_text(encoding='utf-8'));errors=[]
    for entry in manifest['files']:
        p=(ROOT/entry['path']).resolve()
        if ROOT not in p.parents or not p.is_file():errors.append('missing/unsafe: '+entry['path']);continue
        if p.stat().st_size!=entry['sizeBytes'] or hashlib.sha256(p.read_bytes()).hexdigest()!=entry['sha256']:
            errors.append('changed: '+entry['path'])
    if errors:print('\n'.join(errors));return 1
    print(f"Integrity verified: {len(manifest['files'])} files. Local edits after import are expected to change checksums.");return 0
if __name__=='__main__':sys.exit(main())
