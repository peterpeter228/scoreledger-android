#!/usr/bin/env python3
"""Transparent bootstrap, NOT the official Gradle wrapper.
Prefer an existing exact version; otherwise verify the official distribution SHA256.
Run only in an environment where installing build dependencies is authorized.
"""
import hashlib, os, re, shutil, stat, subprocess, sys, tempfile, urllib.request, zipfile
from pathlib import Path, PurePosixPath
VERSION='8.13'
ROOT=Path(__file__).resolve().parents[1]
BASE=f'https://services.gradle.org/distributions/gradle-{VERSION}-bin.zip'
MAX_DOWNLOAD=250*1024*1024

def request(url,limit):
    req=urllib.request.Request(url,headers={'User-Agent':'ScoreLedger-build-bootstrap/1.0'})
    with urllib.request.urlopen(req,timeout=60) as response:
        if not response.url.startswith('https://'):raise RuntimeError('Insecure redirect rejected')
        result=response.read(limit+1)
        if len(result)>limit:raise RuntimeError('Download exceeded size bound')
        return result

def main():
    if '--bootstrap-help' in sys.argv:
        print('Uses installed Gradle 8.13, else HTTPS-downloads and verifies official Gradle 8.13. No SDK or credentials installed.');return 0
    installed=shutil.which('gradle')
    if installed:
        p=subprocess.run([installed,'--version'],capture_output=True,text=True,timeout=60)
        if p.returncode==0 and re.search(r'^Gradle 8\.13\s*$',p.stdout,re.M):
            return subprocess.run([installed,*sys.argv[1:]],cwd=ROOT).returncode
    cache=Path(os.environ.get('GRADLE_USER_HOME',str(Path.home()/'.gradle')))/'scoreledger-bootstrap'
    home=cache/f'gradle-{VERSION}'; binary=home/'bin'/('gradle.bat' if os.name=='nt' else 'gradle')
    if not binary.is_file():
        print('Downloading fixed Gradle 8.13 from the official distribution service; no account permissions are changed.',flush=True)
        expected=request(BASE+'.sha256',1024).decode('ascii').strip().split()[0].lower()
        if not re.fullmatch('[a-f0-9]{64}',expected):raise RuntimeError('Invalid official checksum response')
        cache.mkdir(parents=True,exist_ok=True)
        with tempfile.TemporaryDirectory(prefix='staging-',dir=cache) as temp:
            stage=Path(temp); archive=stage/'gradle.zip'; digest=hashlib.sha256();count=0
            with urllib.request.urlopen(BASE,timeout=60) as response,archive.open('wb') as output:
                if not response.url.startswith('https://'):raise RuntimeError('Insecure redirect rejected')
                while True:
                    chunk=response.read(1024*1024)
                    if not chunk:break
                    count+=len(chunk)
                    if count>MAX_DOWNLOAD:raise RuntimeError('Distribution too large')
                    digest.update(chunk);output.write(chunk)
            if digest.hexdigest()!=expected:raise RuntimeError('SHA256 mismatch; refusing to execute')
            unpack=stage/'unpack';unpack.mkdir()
            with zipfile.ZipFile(archive) as z:
                if sum(i.file_size for i in z.infolist())>1024**3:raise RuntimeError('Unexpected extracted size')
                for item in z.infolist():
                    p=PurePosixPath(item.filename)
                    if p.is_absolute() or '..' in p.parts or '\\' in item.filename or ':' in item.filename:raise RuntimeError('Unsafe archive path')
                    if not p.parts or p.parts[0]!=f'gradle-{VERSION}':raise RuntimeError('Unexpected distribution root')
                    if stat.S_ISLNK(item.external_attr>>16):raise RuntimeError('Symlinks not accepted in distribution')
                z.extractall(unpack)
            extracted=unpack/f'gradle-{VERSION}'
            if not (extracted/'bin/gradle').is_file():raise RuntimeError('Missing Gradle executable')
            if home.exists():raise RuntimeError('Partial cache exists; inspect it rather than overwrite automatically')
            shutil.move(str(extracted),str(home))
            (home/'VERIFIED_SHA256.txt').write_text(expected+'\n',encoding='ascii')
        if os.name!='nt':binary.chmod(0o755)
    return subprocess.run([str(binary),*sys.argv[1:]],cwd=ROOT).returncode
if __name__=='__main__':
    try:sys.exit(main())
    except (OSError,ValueError,RuntimeError,zipfile.BadZipFile,subprocess.TimeoutExpired) as e:
        print(f'BUILD BOOTSTRAP FAILED: {type(e).__name__}: {e}\nRecord this error. Do not fabricate APKs or silently switch versions.',file=sys.stderr);sys.exit(1)
