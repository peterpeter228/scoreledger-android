#!/usr/bin/env python3
"""Report tool availability without collecting secrets or changing accounts."""
import argparse, json, os, platform, shutil, subprocess
from datetime import datetime, timezone
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]
def version(command):
    try:
        p=subprocess.run(command,capture_output=True,text=True,timeout=15)
        return {'exitCode':p.returncode,'summary':(p.stdout+p.stderr).strip().splitlines()[:4]}
    except (OSError,subprocess.TimeoutExpired) as e:
        return {'status':'UNAVAILABLE','reason':type(e).__name__}
def main():
    parser=argparse.ArgumentParser();parser.add_argument('--out');args=parser.parse_args()
    sdk=os.environ.get('ANDROID_SDK_ROOT') or os.environ.get('ANDROID_HOME')
    data={'timestampUtc':datetime.now(timezone.utc).isoformat(),'platform':platform.platform(),
        'python':platform.python_version(),'tools':{},'androidSdk':{'configured':bool(sdk),'exists':bool(sdk and Path(sdk).is_dir())},
        'freeDiskGiB':round(shutil.disk_usage(ROOT).free/1024**3,2),
        'network':'NOT_PROBED','githubAuthorization':'NOT_PROBED','deviceInstallation':'NOT_RUN',
        'secrets':'not read or included'}
    for tool,argv in [('java',['java','-version']),('javac',['javac','-version']),('kotlinc',['kotlinc','-version']),('gradle',['gradle','--version']),('adb',['adb','version']),('git',['git','--version'])]:
        data['tools'][tool]=version(argv) if shutil.which(tool) else {'status':'NOT_FOUND'}
    if sdk and Path(sdk).is_dir():
        data['androidSdk']['platforms']=[p.name for p in (Path(sdk)/'platforms').glob('*') if p.is_dir()]
        data['androidSdk']['buildTools']=[p.name for p in (Path(sdk)/'build-tools').glob('*') if p.is_dir()]
    text=json.dumps(data,ensure_ascii=False,indent=2);print(text)
    if args.out:
        out=Path(args.out);out.parent.mkdir(parents=True,exist_ok=True);out.write_text(text+'\n',encoding='utf-8')
if __name__=='__main__':main()
