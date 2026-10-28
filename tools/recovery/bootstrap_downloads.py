#!/usr/bin/env python3
"""Download official pinned archives atomically; no builds or device operations."""
import concurrent.futures
import datetime
import hashlib
import json
import os
from pathlib import Path
import subprocess
import tarfile
import tempfile
import zipfile
import xml.etree.ElementTree as ET

SOURCE = Path(__file__).resolve().parent
ROOT = Path(os.environ['NATIVE_TOOLCHAIN_ROOT'])
if not ROOT.is_absolute() or ROOT == SOURCE:
    raise RuntimeError('Select a separate absolute NATIVE_TOOLCHAIN_ROOT for generated files')
(ROOT / 'downloads').mkdir(parents=True, exist_ok=True)
(ROOT / 'receipts').mkdir(parents=True, exist_ok=True)
D = ROOT / 'downloads'
R = ROOT / 'receipts'


def sha(path, algorithm='sha256'):
    with path.open('rb') as stream:
        return hashlib.file_digest(stream, algorithm).hexdigest()


def fetch(item):
    path = D / item['filename']
    if path.exists():
        if sha(path, item['algorithm']) != item['digest'] or sha(path) != item['sha256']:
            raise RuntimeError('Existing archive mismatch; not overwritten: ' + str(path))
    else:
        fd, temporary = tempfile.mkstemp(prefix=item['filename'] + '.', suffix='.partial', dir=D)
        os.close(fd)
        temporary = Path(temporary)
        # No automatic retries or TLS changes. Failed partials are retained for diagnosis.
        subprocess.run(['curl', '--fail', '--location', '--proto', '=https', '--tlsv1.2',
                        '--connect-timeout', '20', '--max-time', '600', '--silent', '--show-error',
                        item['url'], '-o', str(temporary)], check=True)
        if item.get('bytes') is not None and temporary.stat().st_size != item['bytes']:
            raise RuntimeError('Published size mismatch: ' + item['filename'])
        if sha(temporary, item['algorithm']) != item['digest'] or sha(temporary) != item['sha256']:
            raise RuntimeError('Published checksum mismatch: ' + item['filename'])
        if item['filename'].endswith('.zip'):
            with zipfile.ZipFile(temporary) as archive:
                if archive.testzip() is not None:
                    raise RuntimeError('ZIP CRC mismatch: ' + item['filename'])
        else:
            with tarfile.open(temporary) as archive:
                archive.getmembers()
        temporary.rename(path)
    receipt = dict(item, actualBytes=path.stat().st_size, sha256=sha(path), verifiedUtc=datetime.datetime.now(datetime.timezone.utc).isoformat())
    (R / (item['filename'] + '.json')).write_text(json.dumps(receipt, indent=2, sort_keys=True) + '\n')
    print('VERIFIED', item['filename'], receipt['actualBytes'], receipt['sha256'], flush=True)
    return receipt


def main():
    items = json.loads((SOURCE / 'downloads.lock.json').read_text())
    (R / 'download-plan.json').write_text(json.dumps(items, indent=2, sort_keys=True) + '\n')
    with concurrent.futures.ThreadPoolExecutor(max_workers=3) as pool:
        receipts = list(pool.map(fetch, items))
    (R / 'archives-verified.json').write_text(json.dumps(receipts, indent=2, sort_keys=True) + '\n')


if __name__ == '__main__':
    main()
