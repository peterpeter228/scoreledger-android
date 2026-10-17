#!/usr/bin/env python3
"""Run Android-independent Kotlin checks; no Gradle/SDK dependency, no fake pass."""
import shutil, subprocess, sys, tempfile
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]
def main():
    kotlinc=shutil.which('kotlinc');java=shutil.which('java')
    if not kotlinc or not java:
        print('NOT_RUN: kotlinc/java unavailable. Use ./gradlew :core:test in a configured environment.',file=sys.stderr)
        return 2
    sources=sorted((ROOT/'core/src/main/kotlin').rglob('*.kt'))
    sources.append(ROOT/'core/src/test/kotlin/com/example/scoreledger/core/ContractChecks.kt')
    with tempfile.TemporaryDirectory(prefix='scoreledger-core-') as temp:
        jar=Path(temp)/'core-contracts.jar'
        command=[kotlinc,*map(str,sources),'-include-runtime','-d',str(jar)]
        build=subprocess.run(command,timeout=120)
        if build.returncode:return build.returncode
        return subprocess.run([java,'-jar',str(jar)],timeout=60).returncode
if __name__=='__main__':
    try:sys.exit(main())
    except subprocess.TimeoutExpired:
        print('FAIL: compiler or test timeout',file=sys.stderr);sys.exit(1)
