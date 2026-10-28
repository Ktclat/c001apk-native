#!/usr/bin/env python3
"""Atomic Maven Central Robolectric runtime preparation, no test execution."""
import concurrent.futures
import datetime
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import tempfile
import zipfile

SOURCE = Path(__file__).resolve().parent
ROOT = Path(os.environ['NATIVE_TOOLCHAIN_ROOT'])
if not ROOT.is_absolute() or ROOT == SOURCE:
    raise RuntimeError('Select a separate absolute NATIVE_TOOLCHAIN_ROOT for generated files')
(ROOT / 'downloads').mkdir(parents=True, exist_ok=True)
(ROOT / 'receipts').mkdir(parents=True, exist_ok=True)
CACHE = ROOT / 'gradle-home/caches/robolectric-home/.m2/repository/org/robolectric/android-all-instrumented'
VERSIONS = {
    26: '8.0.0_r4-robolectric-r1-i7',
    28: '9-robolectric-4913185-2-i7',
    29: '10-robolectric-5803371-i7',
    35: '15-robolectric-13954326-i7',
}


def digest(path, algorithm):
    with path.open('rb') as stream:
        return hashlib.file_digest(stream, algorithm).hexdigest()


def curl(url, target, seconds):
    subprocess.run(['curl', '--fail', '--proto', '=https', '--tlsv1.2', '--silent', '--show-error',
                    '--connect-timeout', '20', '--max-time', str(seconds), url, '-o', str(target)], check=True)


def prepare(pair):
    api, version = pair
    pins = json.loads((SOURCE / 'robolectric.lock.json').read_text())
    folder = CACHE / version
    folder.mkdir(parents=True, exist_ok=True)
    rows = []
    for ext in ('pom', 'jar'):
        name = 'android-all-instrumented-' + version + '.' + ext
        url = 'https://repo.maven.apache.org/maven2/org/robolectric/android-all-instrumented/' + version + '/' + name
        checksum = folder / (name + '.sha512')
        if not checksum.exists():
            with tempfile.NamedTemporaryFile(prefix=name+'.', suffix='.checksum-partial', dir=folder, delete=False) as f:
                temporary = Path(f.name)
            curl(url + '.sha512', temporary, 60)
            pin = temporary.read_text().strip()
            if not re.fullmatch('[0-9a-fA-F]{128}', pin):
                raise RuntimeError('Invalid published SHA512 for ' + name)
            temporary.rename(checksum)
        pin = checksum.read_text().strip().lower()
        expected = next(x for x in pins if x['api'] == api and x['version'] == version and x['url'] == url)
        if pin != expected['sha512']:
            raise RuntimeError('Published checksum changed from reviewed fixed lock')
        if not re.fullmatch('[0-9a-f]{128}', pin):
            raise RuntimeError('Invalid local checksum metadata')
        target = folder / name
        if not target.exists():
            with tempfile.NamedTemporaryFile(prefix=name+'.', suffix='.partial', dir=folder, delete=False) as f:
                temporary = Path(f.name)
            curl(url, temporary, 600)
            if digest(temporary, 'sha512') != pin:
                raise RuntimeError('Downloaded SHA512 mismatch; partial retained, not published: ' + name)
            if ext == 'jar':
                with zipfile.ZipFile(temporary) as z:
                    if z.testzip() is not None:
                        raise RuntimeError('Invalid JAR ZIP CRC: ' + name)
            temporary.rename(target)
        if digest(target, 'sha512') != pin or target.stat().st_size != expected['bytes']:
            raise RuntimeError('Existing runtime mismatch; not overwritten: ' + name)
        if ext == 'jar':
            with zipfile.ZipFile(target) as z:
                if z.testzip() is not None:
                    raise RuntimeError('Existing runtime ZIP CRC mismatch')
        rows.append({'api': api, 'version': version, 'url': url, 'path': str(target),
                     'bytes': target.stat().st_size, 'sha512': pin, 'sha256': digest(target, 'sha256'),
                     'zipCrcPassed': ext == 'jar'})
    print('VERIFIED Robolectric SDK', api, flush=True)
    return rows


def main():
    with concurrent.futures.ThreadPoolExecutor(max_workers=2) as pool:
        rows = [row for group in pool.map(prepare, VERSIONS.items()) for row in group]
    receipt = {'verifiedUtc': datetime.datetime.now(datetime.timezone.utc).isoformat(), 'artifacts': rows}
    (ROOT / 'receipts/robolectric-cache.json').write_text(json.dumps(receipt, indent=2, sort_keys=True)+'\n')


if __name__ == '__main__':
    main()
