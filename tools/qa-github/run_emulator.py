#!/usr/bin/env python3
"""One disposable CI guest, first failure stops. Requires reviewed explicit manual dispatch."""
import hashlib
import json
import ipaddress
import os
from pathlib import Path
import re
import shutil
import signal
import socket
import subprocess
import sys
import time
import xml.etree.ElementTree as ET

ROOT=Path.cwd(); PRIVATE=Path(os.environ['QA_PRIVATE']); PUBLIC=Path(os.environ['QA_PUBLIC'])
SDK=Path(os.environ['ANDROID_HOME']); ADB=str(SDK/'platform-tools/adb')
SERIAL='emulator-5580'; SERVER='5038'; PROXY_PORT=18889
PACKAGE='io.github.ktclat.c001apk.nativeapp'; RUNNER=PACKAGE+'.test/androidx.test.runner.AndroidJUnitRunner'
ENV=os.environ.copy(); ENV.update(HOME=str(PRIVATE/'home'),ANDROID_USER_HOME=str(PRIVATE/'home/.android'),
 ANDROID_AVD_HOME=str(PRIVATE/'home/.android/avd'),ANDROID_EMULATOR_HOME=str(PRIVATE/'emulator-home'),
 XDG_RUNTIME_DIR=str(PRIVATE/'runtime'),ANDROID_I_WANT_MY_TCG='yes',
 ANDROID_ADB_SERVER_PORT=SERVER,ADB_SERVER_SOCKET='tcp:127.0.0.1:'+SERVER)
# No inherited proxy changes for the host or SDK downloads. Only the emulator launch has a reject proxy.
for key in ('HTTP_PROXY','HTTPS_PROXY','ALL_PROXY','http_proxy','https_proxy','all_proxy'):
    ENV.pop(key,None)
for path in ('emulator-home','runtime','home/.android'):
    (PRIVATE/path).mkdir(parents=True,exist_ok=True)
os.chmod(PRIVATE/'runtime',0o700)


def save(name,value):
    target=PUBLIC/name;target.parent.mkdir(parents=True,exist_ok=True)
    temp=target.with_suffix(target.suffix+'.tmp');temp.write_text(json.dumps(value,indent=2,sort_keys=True)+'\n');temp.replace(target)
def sha(path): return hashlib.sha256(path.read_bytes()).hexdigest()
def command(args,seconds=15,check=True):
    result=subprocess.run(args,cwd=ROOT,env=ENV,stdout=subprocess.PIPE,stderr=subprocess.PIPE,timeout=seconds)
    if check and result.returncode: raise RuntimeError('Task command failed: '+Path(args[0]).name)
    return result

def adb_owner_ready():
    process=processes.get('adb')
    assert process is not None and process.poll() is None, 'Owned ADB server is not alive; no client/autostart allowed'
    fields=Path(f'/proc/{process.pid}/stat').read_text().rsplit(')',1)[1].split()
    start_ticks=int(fields[19])
    assert start_ticks==adb_start_ticks, 'Owned ADB identity changed'
    held=set()
    for fd in Path(f'/proc/{process.pid}/fd').iterdir():
        try: link=os.readlink(fd)
        except FileNotFoundError: continue
        match=re.fullmatch(r'socket:\[(\d+)\]',link)
        if match: held.add(match[1])
    listeners=[]
    for table in ('tcp','tcp6'):
        for line in Path('/proc/net/'+table).read_text().splitlines()[1:]:
            parts=line.split();address=parts[1]
            if parts[3]!='0A' or int(address.rsplit(':',1)[1],16)!=int(SERVER): continue
            raw=address.split(':')[0]
            ip=ipaddress.ip_address(b''.join(bytes.fromhex(raw[i:i+8])[::-1] for i in range(0,len(raw),8)))
            assert ip.is_loopback and parts[9] in held, 'Unknown or non-loopback ADB listener; no client command permitted'
            listeners.append(parts[9])
    assert listeners, 'Owned ADB server has no proven listening socket'
    assert process.poll() is None, 'Owned ADB exited during readiness check'
    return {'pid':process.pid,'startTicks':start_ticks,'listenerSocketInodes':sorted(listeners),'port':int(SERVER),'ownedListening':True}

def adb(*args,seconds=15,check=True):
    adb_owner_ready()
    return command([ADB,'-P',SERVER,'-s',SERIAL,*args],seconds,check)
def shell(*args,**kwargs): return adb('shell',*args,**kwargs)
def text(result): return result.stdout.decode('utf-8','replace').strip()
def alive(process,label):
    if process.poll() is not None: raise RuntimeError(label+' exited before cleanup')
def counters():
    value=json.loads((PRIVATE/'proxy.json').read_text());assert value['state']=='ready' and value['forwarded']==0
    return value['counts']


def no_dns_listener():
    # Read-only minimal port occupancy, not a process/environment dump; no privileged bind.
    occupied=[];other=[]
    for table in ('tcp','tcp6','udp','udp6'):
        for line in Path('/proc/net/'+table).read_text().splitlines()[1:]:
            address=line.split()[1]
            if int(address.rsplit(':',1)[1],16)!=53: continue
            raw=address.split(':')[0]
            packed=b''.join(bytes.fromhex(raw[i:i+8])[::-1] for i in range(0,len(raw),8))
            ip=ipaddress.ip_address(packed)
            covers=(ip.is_unspecified or str(ip)=='127.0.0.1' or getattr(ip,'ipv4_mapped',None)==ipaddress.ip_address('127.0.0.1'))
            (occupied if covers else other).append(table)
    receipt=PUBLIC/'dns-boundary.json'
    prior=json.loads(receipt.read_text()).get('observations',[]) if receipt.exists() else []
    prior.append({'monotonic':time.monotonic(),'targetCoveredByTables':occupied,'otherAddressPort53Tables':other})
    save('dns-boundary.json',{'emulatorDnsServer':'127.0.0.1','observations':prior,
        'scope':'Emulator DNS launch path only, no general UDP or host sandbox claim'})
    assert not occupied, 'Existing DNS listener makes loopback DNS assumption unsafe'


def stable_os_frame(label,drawer=False):
    # No active instrumentation/second UiAutomation exists at this phase.
    time.sleep(1.5);previous=None;stable=0;started=time.monotonic()
    while time.monotonic()-started<20:
        raw=adb('exec-out','screencap','-p').stdout
        assert raw.startswith(b'\x89PNG\r\n\x1a\n')
        digest=hashlib.sha256(raw).hexdigest();stable=stable+1 if digest==previous else 1;previous=digest
        (PUBLIC/(label+'.png')).write_bytes(raw)
        if stable>=3: break
        time.sleep(.3)
    assert stable>=3, 'OS compositor did not settle'
    shell('uiautomator','dump','/sdcard/qa-os.xml',seconds=20)
    hierarchy=adb('exec-out','cat','/sdcard/qa-os.xml').stdout
    (PUBLIC/(label+'.xml')).write_bytes(hierarchy)
    nodes=list(ET.fromstring(hierarchy).iter('node'))
    assert any(n.get('package')=='com.android.launcher3' for n in nodes), 'Launcher is not current'
    assert not any('isn\'t responding' in (n.get('text','')+n.get('content-desc','')) for n in nodes), 'OS ANR visible'
    if drawer: assert any(n.get('text')=='Settings' for n in nodes), 'Drawer checkpoint missing'
    save(label+'.json',{'stableFrames':stable,'sha256':digest,'drawerSettingsObserved':drawer,'independentPixelReview':'PENDING'})


def collect(run_id):
    remote=f'/sdcard/Android/data/{PACKAGE}/files/independent-emulator-qa/{run_id}'
    destination=PRIVATE/'collected'/run_id;destination.parent.mkdir(parents=True,exist_ok=True)
    result=adb('pull',remote,str(destination),seconds=45,check=False)
    if result.returncode: return False
    for source in destination.rglob('*'):
        if source.is_dir(): continue
        assert not source.is_symlink() and source.stat().st_size<=20*1024**2
        relative=source.relative_to(destination)
        assert all(re.fullmatch(r'[A-Za-z0-9_.-]+',part) for part in relative.parts)
        assert source.suffix.lower() in ('.png','.json','.txt','.xml','.tsv','.csv'), 'Unknown evidence type: do not upload'
        target=PUBLIC/'runtime'/run_id/relative;target.parent.mkdir(parents=True,exist_ok=True);shutil.copyfile(source,target)
    return True


def instrument(selectors,label,seconds=240,need_evidence=True,need_png=True):
    global sequence
    sequence+=1;run_id=f'gh{os.environ["GITHUB_RUN_ID"]}_{os.environ["GITHUB_RUN_ATTEMPT"]}_{sequence:02}_{label}'
    assert len(run_id)<=64
    path=PUBLIC/'runner'/(run_id+'.log');path.parent.mkdir(parents=True,exist_ok=True)
    started=time.monotonic();failure=None;pulled=False;diagnostics=[];process=None
    try:
        with path.open('wb') as output:
            adb_owner_ready()
            process=subprocess.Popen([ADB,'-P',SERVER,'-s',SERIAL,'shell','am','instrument','-w','-r',
                '-e','qaEvidenceRun',run_id,'-e','class',','.join(selectors),RUNNER],env=ENV,
                stdout=output,stderr=subprocess.STDOUT)
            while process.poll() is None:
                alive(processes['proxy'],'Proxy');alive(processes['emulator'],'Emulator');adb_owner_ready()
                if time.monotonic()-started>=seconds: raise TimeoutError('runner_deadline_no_pass')
                time.sleep(.2)
            if process.returncode: raise RuntimeError('adb_nonzero')
    except BaseException as error:
        failure=type(error).__name__+': '+str(error)
        # Durable primary before any potentially failing Android/collection operation.
        save('runner/'+run_id+'-failure.json',{'failure':failure,'selectors':selectors,'elapsedSeconds':time.monotonic()-started})
        for name,action in [
            ('originalDiagnostic',lambda:(PUBLIC/'runner'/(run_id+'-diagnostic.png')).write_bytes(adb('exec-out','screencap','-p',seconds=5).stdout)),
            ('stopOwnedInstrumentation',lambda:shell('am','force-stop',PACKAGE,seconds=5))]:
            try: action()
            except BaseException as secondary: diagnostics.append(name+':'+type(secondary).__name__)
    finally:
        if process is not None and process.poll() is None:
            process.terminate()  # only this unreaped child; never a searched/reused PID
            try: process.wait(timeout=5)
            except subprocess.TimeoutExpired: process.kill();process.wait(timeout=5)
        try: pulled=collect(run_id)
        except BaseException as error: diagnostics.append('collect:'+type(error).__name__)
    parsed=command(['python3','docs/qa-recovery/verified-tools/summarize-instrumentation.py',str(path)],20)
    summary=json.loads(parsed.stdout);records=summary['logs'][0]['invocations']
    seen=[r['scenario'] for r in records]
    from runner_terminal import qualify
    whole_runner=qualify(path.read_bytes())
    original_pngs=list((PUBLIC/'runtime'/run_id).rglob('*.png'))
    required=[]
    try:
        from evidence_contract import validate
        for selector in selectors: required.extend(validate(PUBLIC/'runtime'/run_id,selector))
    except BaseException as contract_error:
        failure=failure or 'EvidenceContract:'+type(contract_error).__name__+': '+str(contract_error)
    passed=(not failure and whole_runner['qualified'] and (pulled or not need_evidence) and (original_pngs or not need_png) and sorted(seen)==sorted(selectors) and all(r.get('status')=='PASS' and r.get('had_start') for r in records))
    passed=bool(passed)
    save('runner/'+run_id+'.json',{'selectors':selectors,'elapsedSeconds':time.monotonic()-started,
        'rawSha256':sha(path),'records':records,'wholeRunner':whole_runner,'evidencePulled':pulled,'requiredFiles':required,'originalPngCount':len(original_pngs),'qualifiedTerminal':passed,'failure':failure,'diagnostics':diagnostics,'independentOriginalReview':'PENDING'})
    assert passed, 'Instrumentation failed/incomplete; remaining cases NOT_RUN'
    return run_id


def installed_receipt(stage):
    lines=text(shell('pm','path',PACKAGE)).splitlines();assert len(lines)==1 and lines[0].startswith('package:')
    pulled=PRIVATE/(stage+'-installed.apk');adb('pull',lines[0][8:],str(pulled),seconds=45)
    original=ROOT/'app/build/outputs/apk/debug/app-debug.apk'
    binding=json.loads((PUBLIC/'binding.json').read_text())
    assert sha(original)==binding['productionSha256'], 'Built APK drifted after binding'
    assert pulled.read_bytes()==original.read_bytes(), 'Installed production APK differs byte-for-byte'
    save(stage+'-installed.json',{'sha256':sha(pulled),'bytes':pulled.stat().st_size,'byteComparison':True})


def installed_test_receipt(stage):
    lines=text(shell('pm','path',PACKAGE+'.test')).splitlines();assert len(lines)==1 and lines[0].startswith('package:')
    pulled=PRIVATE/(stage+'-installed-test.apk');adb('pull',lines[0][8:],str(pulled),seconds=45)
    original=ROOT/'app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk'
    binding=json.loads((PUBLIC/'binding.json').read_text())
    assert sha(original)==binding['testSha256'] and pulled.read_bytes()==original.read_bytes()
    save(stage+'-installed-test.json',{'sha256':sha(pulled),'bytes':pulled.stat().st_size,'byteComparison':True})


sequence=0;processes={};files=[];failure=None;original_size=None;adb_start_ticks=None
plan=json.loads((ROOT/'docs/qa-recovery/new-plan/case-plan.json').read_text())
app_selectors=[case['selector'] for case in plan['appCases']]
control_selectors=[case['selector'] for case in plan['measurementControls']]
narrow_selectors=[case['selector'] for case in plan['narrowRepeats']]
assert len(app_selectors)==len(set(app_selectors))==15
assert len(control_selectors)==len(set(control_selectors))==6
assert len(narrow_selectors)==len(set(narrow_selectors))==3 and set(narrow_selectors)<=set(app_selectors)
assert not set(app_selectors)&set(control_selectors)
assert len(plan['egressPreflightsSeparate'])==2 and len({case['selector'] for case in plan['egressPreflightsSeparate']})==2
assert plan['targetCounts']=={'appUnique':15,'appInvocationsIncludingNarrow':18,'measurementControlsSeparate':6}
assert all(re.fullmatch(r'io\.github\.ktclat\.c001apk\.nativeapp\.ui\.\w+#\w+',value) for value in app_selectors+control_selectors)
status={'app':[{**case,'status':'NOT_RUN'} for case in plan['appCases']],
 'narrow':[{**case,'status':'NOT_RUN'} for case in plan['narrowRepeats']],
 'controls':[{**case,'status':'NOT_RUN'} for case in plan['measurementControls']],
 'networkBoundary':'NOT_RUN','independentUiReview':'PENDING','liveCdnRestoration':'NOT_APPROVED'}
cleanup={};save('case-status.json',status)
try:
    no_dns_listener()
    for port in (int(SERVER),5580,5581,PROXY_PORT):
        with socket.socket() as check: check.bind(('127.0.0.1',port))
    output=(PRIVATE/'proxy.log').open('wb');files.append(output)
    processes['proxy']=subprocess.Popen([sys.executable,'tools/qa-github/reject_proxy.py','--port',str(PROXY_PORT),
        '--receipt',str(PRIVATE/'proxy.json'),'--ready',str(PRIVATE/'proxy.ready')],env=ENV,stdout=output,stderr=subprocess.STDOUT)
    started=time.monotonic()
    while not (PRIVATE/'proxy.ready').exists():
        alive(processes['proxy'],'Proxy');assert time.monotonic()-started<5;time.sleep(.05)
    # This host-only fixed-line check proves the exact 502 bytes, not Android TLS success.
    with socket.create_connection(('127.0.0.1',PROXY_PORT),timeout=3) as client:
        client.sendall(b'CONNECT 198.51.100.17:443 HTTP/1.1\r\n')
        body=b''
        while True:
            part=client.recv(1024)
            if not part: break
            body+=part
    sys.path.insert(0,str(ROOT/'tools/qa-github'));from reject_proxy import RESPONSE
    assert body==RESPONSE
    started=time.monotonic()
    while counters()['default_https'] != 1:
        assert time.monotonic()-started<3, 'Host selfcheck counter not durably recorded'
        time.sleep(.02)
    save('proxy-selfcheck.json',{'fixedResponseExact':True,'status':502,'upstreamConnections':0})
    output=(PRIVATE/'adb.log').open('wb');files.append(output)
    processes['adb']=subprocess.Popen([ADB,'-P',SERVER,'nodaemon','server'],env=ENV,stdout=output,stderr=subprocess.STDOUT)
    adb_start_ticks=int(Path(f'/proc/{processes["adb"].pid}/stat').read_text().rsplit(')',1)[1].split()[19])
    started=time.monotonic()
    while True:
        alive(processes['adb'],'Owned ADB server')
        try: ownership=adb_owner_ready();break
        except AssertionError:
            assert time.monotonic()-started<5,'Owned ADB listening readiness deadline'
            time.sleep(.05)
    save('adb-owned-listener.json',ownership)
    output=(PRIVATE/'emulator.log').open('wb');files.append(output)
    args=[str(SDK/'emulator/emulator'),'-avd','native_api26_ci_qa','-port','5580','-accel','off','-gpu','swiftshader',
        '-memory','1536','-cores','2','-no-window','-no-audio','-no-snapshot','-no-boot-anim',
        '-http-proxy',f'http://127.0.0.1:{PROXY_PORT}','-dns-server','127.0.0.1']
    processes['emulator']=subprocess.Popen(args,env=ENV,stdout=output,stderr=subprocess.STDOUT)
    save('emulator-launch.json',{'arguments':args[1:],'softwareOnly':True,'physicalGeometry':'720x1280/320dpi','status':'BOOTING'})
    started=time.monotonic()
    while True:
        alive(processes['emulator'],'Emulator');alive(processes['proxy'],'Proxy')
        if text(shell('getprop','sys.boot_completed',seconds=5,check=False))=='1': break
        assert time.monotonic()-started<480,'Boot deadline';time.sleep(2)
    assert text(shell('getprop','ro.build.version.sdk'))=='26'
    assert text(shell('wm','size'))=='Physical size: 720x1280'
    assert text(shell('wm','density'))=='Physical density: 320'
    shell('input','keyevent','82');shell('input','keyevent','3')
    stable_os_frame('boot-home')
    shell('input','swipe','360','1110','360','250','500');stable_os_frame('boot-drawer',True)
    shell('input','keyevent','4');stable_os_frame('boot-return-home')
    prod=ROOT/'app/build/outputs/apk/debug/app-debug.apk';test=ROOT/'app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk'
    binding=json.loads((PUBLIC/'binding.json').read_text())
    assert sha(prod)==binding['productionSha256'] and sha(test)==binding['testSha256'], 'APK binding changed before install'
    adb('install',str(prod),seconds=90);installed_receipt('initial');adb('install',str(test),seconds=90);installed_test_receipt('initial')
    status['networkBoundary']='RUNNING';save('case-status.json',status)
    for method,key in [('defaultHttpsIsRejectedBeforeTls','default_https'),('productionMediaIsRejectedBeforeTls','production_media')]:
        alive(processes['proxy'],'Proxy');before=counters()[key]
        instrument(['io.github.ktclat.c001apk.nativeapp.ui.EmulatorRejectProxyBoundaryTest#'+method],'boundary',45,need_png=False)
        after=counters()[key];assert after>before,'No matching TCP proxy rejection: no app scenarios permitted'
        save('boundary-'+key+'.json',{'before':before,'after':after,'fixed502Written':True,'clientFailed':True,'upstream':0})
    status['networkBoundary']='PASS';save('case-status.json',status)
    deadline=time.monotonic()+35*60
    def one(group,index,label):
        assert time.monotonic()<deadline,'Scenario budget exhausted'
        alive(processes['proxy'],'Proxy');alive(processes['emulator'],'Emulator');no_dns_listener()
        entry=status[group][index];entry['status']='RUNNING';save('case-status.json',status)
        try:
            entry['runId']=instrument([entry['selector']],label,min(240,max(1,int(deadline-time.monotonic()))),need_evidence=group!='controls',need_png=group!='controls')
            entry['status']='PASS'
        except BaseException:
            entry['status']='FAILED_OR_INCOMPLETE';raise
        finally: save('case-status.json',status)
    copy_index=next(i for i,e in enumerate(status['app']) if e['selector'].endswith('#fullReaderRawAndActualCopyPreserveOriginalTokensAndCode'))
    gif_index=next(i for i,e in enumerate(status['app']) if e['selector'].endswith('#productionGifDecoderAnimatesOriginalWhileGridUsesStaticThumbnail'))
    one('app',copy_index,'copy')
    for i in range(len(status['controls'])): one('controls',i,'control')
    one('app',gif_index,'gif')
    for i in range(len(status['app'])):
        if i not in (copy_index,gif_index): one('app',i,'app')
    original_size=text(shell('wm','size'));assert original_size=='Physical size: 720x1280'
    shell('wm','size','640x1200')
    assert 'Override size: 640x1200' in text(shell('wm','size'))
    for i in range(len(status['narrow'])): one('narrow',i,'narrow')
except BaseException as error:
    failure=type(error).__name__+': '+str(error)
    if status['networkBoundary']=='RUNNING': status['networkBoundary']='BLOCKED_OR_FAILED'
    save('gate-failure.json',{'failure':failure,'remaining':'NOT_RUN','uiApproval':'NOT_APPROVED'})
    if processes.get('emulator') is not None and processes['emulator'].poll() is None:
        try:
            raw=adb('exec-out','screencap','-p',seconds=5).stdout
            if raw.startswith(b'\x89PNG\r\n\x1a\n'): (PUBLIC/'gate-diagnostic.png').write_bytes(raw)
        except BaseException: pass  # original durable failure above remains authoritative
finally:
    # Best effort steps each receive their own bounded receipt; never replace primary failure.
    def cleanup_step(name,callback):
        try: callback();cleanup[name]='PASS'
        except BaseException as error: cleanup[name]='FAILED:'+type(error).__name__
    def exit_zero(process):
        code=process.wait(timeout=30)
        assert code==0, 'Owned child exit was not normal zero'
    if processes.get('emulator') is not None and processes['emulator'].poll() is not None:
        cleanup['emulatorUnexpectedExit']='FAILED:guest exited before final binding/sync'
    if processes.get('emulator') is not None and processes['emulator'].poll() is None:
        if original_size is not None:
            def restore_display():
                shell('wm','size','reset')
                assert text(shell('wm','size'))==original_size
            cleanup_step('displayRestore',restore_display)
        cleanup_step('finalInstalledBinding',lambda:installed_receipt('final'))
        cleanup_step('finalTestInstalledBinding',lambda:installed_test_receipt('final'))
        cleanup_step('guestSync',lambda:shell('sync',seconds=10))
        cleanup_step('normalEmulatorQuit',lambda:adb('emu','kill',seconds=15))
        cleanup_step('emulatorExit',lambda:exit_zero(processes['emulator']))
    if processes.get('adb') is not None:
        def stop_owned_adb():
            adb_owner_ready()  # dead/replaced server: never send protocol kill to its port
            command([ADB,'-P',SERVER,'kill-server'],10)
        cleanup_step('adbShutdown',stop_owned_adb)
        cleanup_step('adbExit',lambda:exit_zero(processes['adb']))
    if processes.get('proxy') is not None:
        # Signal only our still-unreaped child; no PID/path discovery or old lease access.
        def stop_owned_proxy():
            assert processes['proxy'].poll() is None, 'Proxy died before owned shutdown'
            processes['proxy'].send_signal(signal.SIGTERM)
        cleanup_step('proxyTerminate',stop_owned_proxy)
        cleanup_step('proxyExit',lambda:exit_zero(processes['proxy']))
        cleanup_step('proxyReceipt',lambda:shutil.copyfile(PRIVATE/'proxy.json',PUBLIC/'proxy-final.json'))
    for index,file in enumerate(files): cleanup_step('fileClose'+str(index),file.close)
    cleanup['remainingDirectChildren']=[name for name,p in processes.items() if p.poll() is None]
    save('cleanup.json',cleanup)
    save('case-status.json',status)
    # Outer owned-core proves descendants too; nonnormal cleanup may never qualify.
    required_cleanup={'finalInstalledBinding','finalTestInstalledBinding','guestSync','normalEmulatorQuit','emulatorExit','adbShutdown','adbExit','proxyTerminate','proxyExit','proxyReceipt'}
    if original_size is not None: required_cleanup.add('displayRestore')
    if cleanup['remainingDirectChildren'] or any(str(v).startswith('FAILED') for v in cleanup.values()) or any(cleanup.get(key)!='PASS' for key in required_cleanup):
        failure=failure or 'Cleanup unqualified'
    save('runtime-verdict.json',{'status':'SCENARIOS_PASS_PENDING_INDEPENDENT_REVIEW' if failure is None else 'FAILED_OR_BLOCKED',
        'primaryFailure':failure,'appUnique':15,'appInvocationsPlanned':18,'controlsPlanned':6,'liveCdnRestoration':'NOT_APPROVED'})
sys.exit(1 if failure else 0)
