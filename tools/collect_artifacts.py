#!/usr/bin/env python3
"""Copy a REAL APK and record integrity metadata. Does not assert installation."""
import argparse, hashlib, json, shutil, sys, zipfile
from datetime import datetime, timezone
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]
def main():
    parser=argparse.ArgumentParser();parser.add_argument('apk');args=parser.parse_args();src=Path(args.apk).resolve()
    if not src.is_file() or src.suffix.lower()!='.apk' or src.stat().st_size<1024:raise ValueError('A real nonempty .apk is required')
    with zipfile.ZipFile(src) as z:
        names=set(z.namelist())
        if 'AndroidManifest.xml' not in names or 'classes.dex' not in names:raise ValueError('APK manifest/dex missing')
        if z.testzip() is not None:raise ValueError('Corrupt APK archive')
    out=ROOT/'out';out.mkdir(exist_ok=True);dest=out/src.name
    if dest.resolve()!=src:shutil.copy2(src,dest)
    data={'file':dest.name,'sizeBytes':dest.stat().st_size,'sha256':hashlib.sha256(dest.read_bytes()).hexdigest(),
        'collectedAt':datetime.now(timezone.utc).isoformat(),'installation':'NOT_VERIFIED',
        'signature':'NOT_VERIFIED','billing':'NOT_VERIFIED','note':'Use apkanalyzer/aapt and apksigner, then record real device installation separately.'}
    (out/(src.stem+'.metadata.json')).write_text(json.dumps(data,indent=2,ensure_ascii=False)+'\n',encoding='utf-8')
    print(json.dumps(data,indent=2,ensure_ascii=False));return 0
if __name__=='__main__':
    try:sys.exit(main())
    except (OSError,ValueError,zipfile.BadZipFile) as e:print(f'FAIL: {e}',file=sys.stderr);sys.exit(1)
