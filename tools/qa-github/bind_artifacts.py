#!/usr/bin/env python3
"""Archive hashes/public certificate identity only; keep APKs and all private keys private."""
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess

root=Path.cwd(); public=Path(os.environ['QA_PUBLIC']); private=Path(os.environ['QA_PRIVATE'])
public.mkdir(parents=True,exist_ok=True)
def call(*args): return subprocess.check_output(args,text=True).strip()
def sha(path): return hashlib.sha256(path.read_bytes()).hexdigest()
assert call('git','rev-parse','HEAD') == os.environ['GITHUB_SHA']
assert not call('git','status','--porcelain','--untracked-files=all'), 'Tracked tree changed during build'
tracked=call('git','ls-files','-z').split('\0')
inputs=[p for p in tracked if p and (p.startswith(('app/src/main/','buildSrc/','gradle/')) or p in ('app/build.gradle.kts','build.gradle.kts','settings.gradle.kts','gradle.properties','gradlew'))]
inputs += ['app/src/main/res/font/noto_sans_cjk_sc_'+weight+'.otf' for weight in ('regular','medium','bold')]
inputs=sorted(set(inputs))
manifest=''.join(f'{sha(root/p)}  {p}\n' for p in sorted(inputs))
(public/'runtime-source.sha256').write_text(manifest)
test_inputs=[p for p in tracked if p and p.startswith(('app/src/androidTest/','tools/qa-github/','tools/recovery/runner/','docs/qa-recovery/new-plan/','docs/qa-recovery/verified-tools/'))]
test_inputs+=['.github/workflows/android.yml','app/build.gradle.kts']
(public/'test-source.sha256').write_text(''.join(f'{sha(root/p)}  {p}\n' for p in sorted(set(test_inputs))))
prod=root/'app/build/outputs/apk/debug/app-debug.apk'
test=root/'app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk'
apksigner=Path(os.environ['ANDROID_HOME'])/'build-tools/36.0.0/apksigner'
certs=[]
for apk in (prod,test):
    output=call(str(apksigner),'verify','--verbose','--print-certs',str(apk))
    matches=re.findall(r'^Signer #\d+ certificate SHA-256 digest: ([0-9a-f]{64})$',output,re.M)
    assert len(matches)==1, 'Missing/ambiguous public signer identity'
    certs.append(matches[0])
assert certs[0]==certs[1], 'Instrumentation signer mismatch'
subprocess.run(['python3','docs/qa-recovery/verified-tools/check-dex-binding.py',str(prod),str(test),str(public/'dex-binding.json')],check=True,stdout=subprocess.DEVNULL)
# Portable public record contains no private filesystem paths.
dex=json.loads((public/'dex-binding.json').read_text());dex['production_apk']='app-debug.apk (not uploaded)';dex['test_apk']='app-debug-androidTest.apk (not uploaded)'
(public/'dex-binding.json').write_text(json.dumps(dex,indent=2)+'\n')
(public/'binding.json').write_text(json.dumps({'sourceCommit':call('git','rev-parse','HEAD'),'sourceTree':call('git','rev-parse','HEAD^{tree}'),
 'productionSha256':sha(prod),'testSha256':sha(test),'productionBytes':prod.stat().st_size,'testBytes':test.stat().st_size,
 'publicSignerSha256':certs[0],'runtimeManifestSha256':sha(public/'runtime-source.sha256'),'runtimeInputs':len(inputs),'testSourceManifestSha256':sha(public/'test-source.sha256'),'testSourceInputs':len(set(test_inputs)),
 'installedBinding':'PENDING','independentUiApproval':'PENDING','liveCdnRestoration':'NOT_APPROVED'},indent=2)+'\n')
