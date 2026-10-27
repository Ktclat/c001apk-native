# Opt-in independent actual-emulator harness

**Approved bounded increment: the exact final production APK passed the actual API 26 emulator gate and independent original-pixel review on 2026-10-27. Earlier 0dd7250 true-landscape warning fragments, 0b5cc7d active-modal contrast, and covered-IME screenshots remain rejected historical evidence; they are not relabeled as final passes.**

This is a **test-only overlay**, not part of the production APK or its dependencies. Apply it only in a separate worktree at the published commit/ref supplied in the final delivery, then verify the portable runtime-source manifest before applying the overlay. The tested runtime uses local build-provenance commit `6a5acbf79d9dc4d94ab609034ea35e0478f58609`; that local commit need not exist in published Git history. A source-equivalent published snapshot is not a claim that its Git identity or newly rebuilt APK bytes equal the archived local APK.

- Patch: `instrumentation-harness-c4f1430.patch`
- Patch SHA256: `a1b02c50afd0cbfa027074ad52454d94596f24dd40fb17e09d4d4d76db2ebad8`
- Harness commit: `c4f1430b7b30691983d500873e955a8ab7fe11d2`
- Built test APK SHA256: `af977b183b51f21faae3ea0fb88b82087267e5be663cbe1332d16c4757e06c58`
- Production APK SHA256: `ea58d71b5614949f326ec84619088442f755f4884609ce62c599ee918b366134` (18,351,484 bytes).
- Runtime verdict: **APPROVED for the documented bounded increment** — 28 distinct scenarios, 30 passed invocations, one intentional portrait-only landscape assumption skip, zero failures/errors. See `RUNTIME-RESULTS.md` and `runtime-scenarios.json`; older-APK passes are excluded.

## Requirements and build

JDK 17, Android SDK/platform 36 and Build Tools 36.0.0, pinned Gradle 8.13 wrapper, official AOSP API 26 x86_64 emulator image. Prepare the repository's pinned fonts with `python3 app/tools/fetch_fonts.py`; verify with `--offline`. Dependencies come from the existing official Google/Maven repositories. No device credentials or real account are needed.

```sh
PUBLISHED_COMMIT="<published commit/ref from the final delivery>"
git worktree add --detach ../native-qa-final "$PUBLISHED_COMMIT"
cd ../native-qa-final
# Verify the unmodified runtime/build whitelist BEFORE applying AndroidTest dependencies.
sha256sum -c docs/qa/runtime-source.sha256
git apply --check /path/to/instrumentation-harness-c4f1430.patch
git apply /path/to/instrumentation-harness-c4f1430.patch
python3 app/tools/fetch_fonts.py
./gradlew --no-daemon --max-workers=1 \
  -Dorg.gradle.jvmargs='-Xmx2g -Dfile.encoding=UTF-8' \
  -Pkotlin.compiler.execution.strategy=in-process :app:assembleDebugAndroidTest
```

Use the separately built, full-gate-approved **production APK**, never the app APK assembled by this modified build. Production and test APKs must have matching debug signers. Do not uninstall/clear data to work around a signing mismatch. Record production source fingerprint, both APK hashes, and `qa/collect-source-binding.sh` output before installation (pass the checked-out published commit, not the local-only provenance SHA). `runtime-source.sha256` contains 242 relative-path files from the frozen `main-source-whitelist-v1` inputs and reconstructs fingerprint `51d567605f8c94736edee326b0992b5f9b4f4aa43e915acd75cd0d25b9475e7a`; it contains no local paths or credentials. Pinned font bytes are represented by the repository font manifest and must still pass the font tool verification.

## Run only on a disposable task emulator

Use a single owned emulator, initially 720 × 1280 / 320 dpi, with no user accounts or device data. Serialize Gradle and runtime jobs; this task used `heavy-gates.lock`. The task-local `native-toolchain/open-emulator.sh` and `stop-emulator.sh` are optional helpers, **not included in the patch**. The overlay’s `qa/README.md` retains the original task-environment procedure; this document supplies its portable replacement. Standard SDK tools are sufficient. If using the optional `qa/run-instrumentation-session.sh` convenience script, first export `NATIVE_EMU_SERIAL=emulator-5580` and `NATIVE_EMU_OUT=/path/to/a/new/task-evidence-directory`; these variables do not require the task-local helper:

```sh
set -euo pipefail
# Use official SDK Manager packages and complete their normal license setup.
sdkmanager 'platform-tools' 'emulator' 'platforms;android-36' \
  'build-tools;36.0.0' 'system-images;android-26;default;x86_64'
# This deliberately does not --force overwrite an existing AVD. Choose a new task name if needed.
TASK_AVD_DIR="$PWD/native_qa_api26.avd"
test ! -e "$TASK_AVD_DIR" || { echo 'Choose a new, unused task AVD path'; exit 1; }
printf 'no\n' | avdmanager create avd -n native_qa_api26 \
  -p "$TASK_AVD_DIR" -k 'system-images;android-26;default;x86_64'
# Set physical AVD geometry before its first boot. A wm override alone is insufficient:
# the landscape preflight deliberately resets overrides and checks real rotation.
python3 - "$TASK_AVD_DIR/config.ini" <<'PY_CONFIG'
from pathlib import Path
import sys
p = Path(sys.argv[1])
values = {'hw.lcd.width': '720', 'hw.lcd.height': '1280', 'hw.lcd.density': '320'}
lines = [line for line in p.read_text().splitlines()
         if line.partition('=')[0].strip() not in values]
p.write_text('\n'.join(lines + [f'{k}={v}' for k, v in values.items()]) + '\n')
PY_CONFIG
emulator -avd native_qa_api26 -port 5580 -no-window -no-snapshot -no-audio \
  -gpu swiftshader -accel off -memory 1536 -cores 2 > emulator.log 2>&1 &
EMU_PID=$!
# Bash + GNU timeout: bounded boot observation; do not silently continue on failure.
deadline=$((SECONDS + 300))
until [ "$(timeout 5 adb -s emulator-5580 shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = 1 ]; do
  if [ "$SECONDS" -ge "$deadline" ]; then
    echo 'Boot not confirmed; preserve emulator.log, inspect, and stop the owned emulator.' >&2
    exit 1
  fi
  sleep 2
done
adb -s emulator-5580 shell wm size    # Must report physical 720x1280, no override.
adb -s emulator-5580 shell wm density # Must report physical 320, no override.
adb -s emulator-5580 exec-out screencap -p > preflight.png
```

Reopen the actual preflight pixels and confirm responsive Android/home state before testing. A system ANR or failed capture must be diagnosed, not suppressed. Do not use adb root, change firewall/security policy, or reuse a user device. In namespace-isolated execution environments, keep all adb commands in the **same persistent shell** as the emulator. On ordinary local hosts this restriction is not usually necessary.

```sh
adb -s emulator-5580 install -r /path/to/exact-production.apk
adb -s emulator-5580 install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
# Read pm path, pull the installed production base.apk, compare bytes/SHA256 to input.
adb -s emulator-5580 shell am instrument -w -r -e timeout_msec 180000 \
  io.github.ktclat.c001apk.nativeapp.test/androidx.test.runner.AndroidJUnitRunner
adb -s emulator-5580 pull \
  /sdcard/Android/data/io.github.ktclat.c001apk.nativeapp/files/independent-emulator-qa \
  /path/to/new-evidence-directory
```

Run this in background or from a script so the owning shell remains controllable. Do not Ctrl-C the whole persistent session. Use a fresh evidence directory for every production/test APK stage. Preserve previous task output before rerunning; never silently attribute stale files to the new APK.

When finished, restore the task display overrides, preserve logs/evidence, then cleanly stop only this owned emulator:

```sh
adb -s emulator-5580 shell wm size reset
adb -s emulator-5580 shell wm density reset
adb -s emulator-5580 logcat -d > emulator-logcat.txt
adb -s emulator-5580 shell sync
adb -s emulator-5580 emu kill
wait "$EMU_PID"
```

Do not uninstall the application, clear app data, delete existing AVDs, or stop unrelated adb sessions to obtain a passing result.

There are 28 distinct scenarios. The landscape method deliberately assumption-skips on portrait. A width/height swap alone is NOT real rotation on this API 26 image: it produced letterboxing and unavailable UiAutomation captures. For actual landscape, preserve the disposable AVD’s exact `accelerometer_rotation` and `user_rotation` values, reset size overrides, set ordinary `accelerometer_rotation=0` and `user_rotation=1`, then launch unchanged MainActivity (Launcher may lock portrait). Verify display rotation 1 and an original 1280×720 compositor image before running the landscape method. Restore both exact prior values, deleting a setting only if it was originally null. `qa/run-instrumentation-session.sh` implements this supported rotation preflight and restoration. Run the rich dark-font and blacklist dark-font methods again at actual `wm size 640x1200`, density 320. `qa/run-instrumentation-session.sh` documents the exact class/method filters. Restore `wm size reset` and `wm density reset` afterwards. The font stress is an **effective Compose LocalDensity of 2× / 2.4× in the real fixture host**, not an Android system font-setting change.

## Evidence, safety and honest scope

- MainActivity tests launch unmodified production UI. Guest public GETs may fail normally; Home loading/login preparation do not establish live-service access or login success.
- Authenticated fixtures use memory-only credential/local stores and strict deny-default interceptors; no `chain.proceed`. Only allowlisted local GETs and the exact synthetic graphic-validation POST (`type=err_request_captcha`, `code=AB12`) are answered. No real credentials, CAPTCHA provider or social mutation is used.
- Actual Android DocumentsUI Cancel+Save writes only task-owned `42424242` to a unique `native-qa-users-<timestamp>.json` name. Record the observed filename and picker hierarchy, enumerate Downloads, pull that exact newly created file, parse it and hash its bytes. It must be category JSON array `["42424242"]`, not a combined backup; a toast alone is insufficient.
- Blacklist document-boundary scenarios use a fake registry/IO adapter and are explicitly **not** SAF-provider integration evidence.
- Destructive clear-dialog checks require the full initial irreversible-warning phrase and the final backup phrase to fit inside the actual clipped body viewport, using TextLayout glyph bounds, effective font scale 2.4 and minimum rendered-line height. Both actions remain displayed and at least 48 dp high. Zero scroll range is valid only if the content genuinely fits; original pixels remain decisive.
- Theme/modal checks record the actual focused WindowManager window, distinguish parent type 1 from dialog type 2, and assert both icon-appearance flags. Font, reading-preset and font-size sheets run in forced Dark and Light, with Back/Close/Cancel and open-sheet recreation.
- After SAF return, the test uses a visible empty-field touch to establish the native IME, observes it positively, dismisses it with normal Back, waits until absent and scrolls the actual list to the displayed export status. No input text or OS policy is changed.
- Captures are original full compositor PNGs. Stability/foreground traces and narrowly bounded null-accessibility-root WindowManager fallback are recorded; no PNG is edited or reconstructed. Rejected captures are saved as diagnostic-failed, never accepted route evidence. Independent review must reopen original pixels.
- Graphic verification requires positive actual IME-window presence, normal Back dismissal, IME absence, the same challenge and exact AB12 input, and unobscured Submit/Cancel original pixels before view-level coordinate pointer dispatch. `performTouchInput` uses `View.dispatchTouchEvent`, not OS WindowManager hit testing. This is keyboard-dismissed reachability, not a claim that buttons were visible with the keyboard open. Active dialog/host window geometry is recorded.
- Cached semantics wrapper replay is UI-node duplicate coverage, not immutable callback-replacement proof. Separate unit tests cover the latter.
- Personal Back refetches; only blacklist child Back claims exact scroll/draft retention. No API 29/35 actual emulator, physical-device, TalkBack, real-account/login, live-service, performance or full Flutter parity claim is made.
