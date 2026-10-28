#!/usr/bin/env python3
"""Fail-closed explicit artifact staging. Never traverse the private workspace for files."""
import hashlib,json,os
from pathlib import Path
import re,shutil

source=Path(os.environ['QA_PUBLIC']);destination=Path.cwd()/'qa-public-artifact'
assert not destination.exists(), 'Refuse to merge stale artifacts'
destination.mkdir()
allowed_top={'adb-owned-listener.json','source-gate.json','official-sdk.json','runtime-source.sha256','test-source.sha256','binding.json','dex-binding.json',
'gate-diagnostic.png','dns-boundary.json','proxy-selfcheck.json','proxy-final.json','emulator-launch.json',
'initial-installed.json','final-installed.json','initial-installed-test.json','final-installed-test.json','case-status.json','gate-failure.json','cleanup.json','runtime-verdict.json',
'boundary-default_https.json','boundary-production_media.json'}
for label in ('boot-home','boot-drawer','boot-return-home'):
    for suffix in ('.png','.xml','.json'): allowed_top.add(label+suffix)
files=[]
if source.exists():
    for path in sorted(source.rglob('*')):
        if path.is_dir(): continue
        relative=path.relative_to(source)
        assert not path.is_symlink() and all(re.fullmatch(r'[A-Za-z0-9_.-]+',part) for part in relative.parts)
        assert path.stat().st_size<=20*1024**2
        permitted=(str(relative) in allowed_top or
            (relative.parts[0]=='runner' and len(relative.parts)==2 and path.suffix in ('.log','.json','.png')) or
            (relative.parts[0]=='runtime' and len(relative.parts)>=3 and path.suffix in ('.png','.json','.txt','.xml','.tsv','.csv')))
        assert permitted,'Unknown file is not publishable: '+str(relative)
        target=destination/relative;target.parent.mkdir(parents=True,exist_ok=True);shutil.copyfile(path,target)
        files.append({'path':str(relative),'bytes':path.stat().st_size,'sha256':hashlib.sha256(path.read_bytes()).hexdigest()})
# No raw owned-process output or environment is published. Extract only terminal identity/status fields.
for stage in ('build','runtime'):
    receipt=Path(os.environ['QA_PRIVATE'])/(stage+'-owner')/'command'/'receipt.json'
    # Supervisor schemas/paths must be reviewed, never interpret a missing receipt as clean.
    if receipt.is_file():
        raw=json.loads(receipt.read_text())
        safe={key:raw.get(key) for key in ('status','exitCode','stopReason')}
        cleanup=raw.get('cleanup') or {}
        safe['cleanup']={'signalCount':len(cleanup.get('signals',[])),'remainingCount':len(cleanup.get('remaining',[])),
            'hasProblem':bool(cleanup.get('problem')),'emptyProof':cleanup.get('proof') in ('two-empty-scans','two-empty-scans-after-blocked')}
        safe['qualified']=safe['status']=='completed' and safe['exitCode']==0 and safe['cleanup']=={'signalCount':0,'remainingCount':0,'hasProblem':False,'emptyProof':True}
    else: safe={'status':'MISSING_TERMINAL_RECEIPT_UNQUALIFIED'}
    (destination/(stage+'-owner-terminal.json')).write_text(json.dumps(safe,indent=2)+'\n')
# Include generated safe owner receipts too, not just copied originals.
files=[{'path':str(path.relative_to(destination)),'bytes':path.stat().st_size,
        'sha256':hashlib.sha256(path.read_bytes()).hexdigest()} for path in sorted(destination.rglob('*')) if path.is_file()]
(destination/'artifact-manifest.json').write_text(json.dumps({'sourceCommit':os.environ.get('GITHUB_SHA'),
'files':files,'approval':'PENDING_INDEPENDENT_ORIGINAL_PIXEL_REVIEW','liveCdn':'NOT_APPROVED',
'privatePayloads':'No APK/key/user-upload/userdata/environment dump included'},indent=2)+'\n')
