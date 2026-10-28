#!/usr/bin/env bash
# Run only after reviewed-source + explicit dispatch. No emulator/adb starts here.
set -euo pipefail
: "${QA_PRIVATE:?}" "${QA_PUBLIC:?}" "${ANDROID_HOME:?}"
mkdir -p "$QA_PRIVATE" "$QA_PUBLIC"
test -z "$(git status --porcelain --untracked-files=all)"
export HOME="$QA_PRIVATE/home" ANDROID_USER_HOME="$QA_PRIVATE/home/.android"
export GRADLE_USER_HOME="$QA_PRIVATE/gradle" ANDROID_AVD_HOME="$ANDROID_USER_HOME/avd"
mkdir -p "$HOME" "$ANDROID_USER_HOME" "$ANDROID_AVD_HOME" "$GRADLE_USER_HOME"
# This job only owns these new directories. It does not touch the blocked local lease.
python3 - "$QA_PRIVATE" <<'PY'
import pathlib, shutil, sys
free=shutil.disk_usage(sys.argv[1]).free
available=int(next(x.split()[1] for x in pathlib.Path('/proc/meminfo').read_text().splitlines() if x.startswith('MemAvailable:')))*1024
assert free >= 12*1024**3 and available >= 6*1024**3, 'Insufficient clean resource budget'
PY
sdkmanager="$(find "$ANDROID_HOME/cmdline-tools" -type f -path '*/bin/sdkmanager' | sort -V | tail -n 1)"
test -x "$sdkmanager"
"$sdkmanager" --sdk_root="$ANDROID_HOME" 'platforms;android-36' 'build-tools;36.0.0' 'platform-tools' 'emulator' 'system-images;android-26;default;x86_64'
python3 - "$ANDROID_HOME" "$QA_PUBLIC" <<'PY'
from pathlib import Path
import hashlib,json,sys
sdk,out=map(Path,sys.argv[1:])
paths=['emulator/source.properties','system-images/android-26/default/x86_64/source.properties','platform-tools/source.properties']
metadata={p:(sdk/p).read_text() for p in paths}
def properties(value):
    return {line.split('=',1)[0].strip():line.split('=',1)[1].strip() for line in value.splitlines() if '=' in line and not line.lstrip().startswith('#')}
assert properties(metadata[paths[0]])['Pkg.Revision']=='37.2.12', 'Unreviewed emulator version'
assert properties(metadata[paths[1]])['Pkg.Revision']=='1', 'Unreviewed image revision'
files={}
for name in ('platform-tools/adb','emulator/emulator','emulator/qemu/linux-x86_64/qemu-system-x86_64','system-images/android-26/default/x86_64/system.img','system-images/android-26/default/x86_64/ramdisk.img','system-images/android-26/default/x86_64/kernel-ranchu'):
    path=sdk/name;digest=hashlib.sha256()
    with path.open('rb') as stream:
        for block in iter(lambda:stream.read(1024*1024),b''): digest.update(block)
    files[name]={'sha256':digest.hexdigest(),'bytes':path.stat().st_size}
(out/'official-sdk.json').write_text(json.dumps({'metadata':metadata,'keyFiles':files,'source':'Official sdkmanager; no third-party action'},indent=2)+'\n')
PY
python3 -m unittest discover -s tools/qa-github -p 'test_*.py' -v
python3 app/tools/fetch_fonts.py
python3 app/tools/fetch_fonts.py --offline
./gradlew --no-daemon --max-workers=1 -Dorg.gradle.parallel=false -Pkotlin.compiler.execution.strategy=in-process :app:assembleDebug :app:assembleDebugAndroidTest
python3 app/tools/verify_emoticon_apk.py app/build/outputs/apk/debug/app-debug.apk
python3 tools/qa-github/bind_artifacts.py
# No Gradle or Kotlin daemon may be left beside the software emulator. Existing owned-core
# must prove the build subtree clean before this step can return qualified.
avdmanager="${sdkmanager%/sdkmanager}/avdmanager"
printf 'no\n' | "$avdmanager" create avd --name native_api26_ci_qa --package 'system-images;android-26;default;x86_64' --path "$QA_PRIVATE/avd"
python3 - "$QA_PRIVATE/avd/config.ini" <<'PY'
from pathlib import Path
import sys
p=Path(sys.argv[1]); keys={'hw.lcd.width':'720','hw.lcd.height':'1280','hw.lcd.density':'320','hw.ramSize':'1536','hw.cpu.ncore':'2','hw.gpu.enabled':'yes','hw.gpu.mode':'swiftshader','hw.keyboard':'no','hw.mainKeys':'no','skin.name':'720x1280','skin.dynamic':'yes','disk.dataPartition.size':'2G','fastboot.forceColdBoot':'yes'}
lines=[line for line in p.read_text().splitlines() if line.partition('=')[0].strip() not in keys]
p.write_text('\n'.join(lines+[f'{key}={value}' for key,value in keys.items()])+'\n')
PY
