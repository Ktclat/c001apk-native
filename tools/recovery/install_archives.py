#!/usr/bin/env python3
"""Materialize verified official archives inside this task only; no build."""
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import shutil
import stat
import tarfile
import tempfile
import zipfile

SOURCE = Path(__file__).resolve().parent
ROOT = Path(os.environ['NATIVE_TOOLCHAIN_ROOT'])
if not ROOT.is_absolute() or ROOT == SOURCE:
    raise RuntimeError('Select a separate absolute NATIVE_TOOLCHAIN_ROOT for generated files')
(ROOT / 'downloads').mkdir(parents=True, exist_ok=True)
(ROOT / 'receipts').mkdir(parents=True, exist_ok=True)
D = ROOT / 'downloads'


def hash_file(path):
    with path.open('rb') as f:
        return hashlib.file_digest(f, 'sha256').hexdigest()


def install_zip(name, destination):
    if destination.exists():
        raise RuntimeError('Refusing to overwrite installation: ' + str(destination))
    temporary = Path(tempfile.mkdtemp(prefix='extract-', dir=ROOT))
    with zipfile.ZipFile(D / name) as z:
        roots = set()
        for info in z.infolist():
            p = PurePosixPath(info.filename)
            mode = info.external_attr >> 16
            if p.is_absolute() or '..' in p.parts or stat.S_ISLNK(mode):
                raise RuntimeError('Unsafe ZIP entry: ' + info.filename)
            roots.add(p.parts[0])
            target = temporary / info.filename
            if info.is_dir():
                target.mkdir(parents=True, exist_ok=True)
            else:
                target.parent.mkdir(parents=True, exist_ok=True)
                with z.open(info) as source, target.open('xb') as output:
                    shutil.copyfileobj(source, output)
                target.chmod(0o755 if mode & 0o111 else 0o644)
        if len(roots) != 1:
            raise RuntimeError('Unexpected archive root layout')
    destination.parent.mkdir(parents=True, exist_ok=True)
    (temporary / roots.pop()).rename(destination)
    temporary.rmdir()


def main():
    receipts = json.loads((ROOT / 'receipts/archives-verified.json').read_text())
    for row in receipts:
        assert hash_file(D / row['filename']) == row['sha256']
    temporary = Path(tempfile.mkdtemp(prefix='extract-jdk-', dir=ROOT))
    destination = ROOT / 'jdk17'
    if destination.exists():
        raise RuntimeError('Existing JDK left unchanged')
    with tarfile.open(D / 'OpenJDK17U-jdk_x64_linux_hotspot_17.0.21_9.tar.gz') as tar:
        tar.extractall(temporary, filter='data')
    (temporary / 'jdk-17.0.21+9').rename(destination)
    temporary.rmdir()
    sdk = ROOT / 'android-sdk'
    install_zip('commandlinetools-linux-13114758_latest.zip', sdk / 'cmdline-tools/19.0')
    install_zip('platform-36_r02.zip', sdk / 'platforms/android-36')
    install_zip('build-tools_r36_linux.zip', sdk / 'build-tools/36.0.0')
    install_zip('platform-tools_r37.0.1-linux.zip', sdk / 'platform-tools')
    install_zip('gradle-8.13-bin.zip', ROOT / 'gradle-8.13')
    # Seed only the verified archive. The official wrapper performs its own
    # checksum verification/extraction on its first --version invocation.
    n = int(hashlib.md5(b'https://services.gradle.org/distributions/gradle-8.13-bin.zip', usedforsecurity=False).hexdigest(), 16)
    alphabet = '0123456789abcdefghijklmnopqrstuvwxyz'; base36 = ''
    while n:
        n, remainder = divmod(n, 36); base36 = alphabet[remainder] + base36
    cache = ROOT / 'gradle-home/wrapper/dists/gradle-8.13-bin' / base36
    cache.mkdir(parents=True, exist_ok=False)
    os.link(D / 'gradle-8.13-bin.zip', cache / 'gradle-8.13-bin.zip')
    defaults = ROOT / 'gradle-home/gradle.properties'
    if defaults.exists() and defaults.read_bytes() != (SOURCE / 'gradle-user.properties').read_bytes():
        raise RuntimeError('Existing user Gradle settings differ; not overwritten')
    shutil.copyfile(SOURCE / 'gradle-user.properties', defaults)
    (ROOT / 'bin').mkdir(exist_ok=True)
    shutil.copyfile(SOURCE / 'adb-task.sh', ROOT / 'bin/adb')
    (ROOT / 'bin/adb').chmod(0o755)
    print('Installed official archives; wrapper seed', cache)


if __name__ == '__main__':
    main()
