# New disposable API26 software AVD

This is a reproducibility specification for the **new Oct28 reconstruction**, not proof that a previous AVD or its screenshots were recovered. Preparing an AVD does not establish successful boot or application acceptance. Only the assigned QA owner may boot it after the new exact APK gate and exclusive-lock handoff.

## Official fixed inputs

`emulator.lock.json` records the official Google URLs, sizes, published SHA-1 and verified SHA-256 for:

- Stable Android Emulator **37.2.12**, build **16428233**, Linux x64.
- **AOSP default API26 x86_64 revision 1** system image (not Google Play and not a user device).

Use the same `bootstrap_downloads.fetch` checksum/ZIP verifier for these two records. Do not replace their hashes to accommodate a newer download. The official Android SDK manager should register/install `emulator` and `system-images;android-26;default;x86_64`; verify its selected versions and installed files against the fixed archives before use. If a future stable release differs, stop and review an explicit version change instead of silently treating it as this configuration.

Directly extracting the emulator ZIP did **not** register it with command-line tools 19: the ZIP lacked `package.xml`, and `avdmanager` reported that `emulator` was missing. A local schema-conversion attempt was rejected too. The new preparation used the **official sdkmanager installation** instead; all 398 installed archive files matched the verified pinned emulator ZIP. Do not reuse the rejected local registration method or bypass an SDK/AVD ownership check.

## Create a fresh task AVD, never overwrite another one

Source `env.sh` with an explicitly selected toolchain root. Ensure both the chosen `.ini` and `.avd` paths do not already exist. Do not pass `--force`, delete locks, or reuse another namespace's AVD based only on a local process list.

```bash
name=native_api26_recovery_YYYYMMDD_unique
test ! -e "$ANDROID_AVD_HOME/$name.ini"
test ! -e "$ANDROID_AVD_HOME/$name.avd"
printf 'no\n' | avdmanager create avd \
  --name "$name" --package 'system-images;android-26;default;x86_64' \
  --device pixel_2 --path "$ANDROID_AVD_HOME/$name.avd"
```

Before its **first boot**, set these keys in this new AVD's `config.ini`, preserving the other generated keys:

```ini
hw.lcd.width=720
hw.lcd.height=1280
hw.lcd.density=320
hw.ramSize=1536
hw.cpu.ncore=2
hw.gpu.enabled=yes
hw.gpu.mode=swiftshader
hw.mainKeys=no
hw.keyboard=no
disk.dataPartition.size=2G
skin.name=720x1280
skin.dynamic=yes
fastboot.forceColdBoot=yes
```

The physical configuration is deliberate: a later ordinary `wm size reset` must restore 720×1280, not a different device profile. Record the final config hash and explicit path in that attempt's evidence. Do not put runtime userdata, QCOW images or generated ADB keys in Git.

## Later launch boundary — not an instruction to boot during setup

After the reviewed runner proves all Gradle descendants exited, the sole QA owner may acquire the shared heavy lock and launch with:

```text
-avd <new-task-name> -accel off -gpu swiftshader -memory 1536 -cores 2
-no-window -no-audio -no-snapshot -no-boot-anim
```

Use the currently provided managed proxy only when network access is in scope, and task-local HOME/runtime directories. ADB must use `adb-task.sh`; do not alter the OS-home permissions. No root, firewall, SELinux, certificate or TLS changes are part of preparation. Do not overlap any Gradle job with the software emulator. Inspect settled Android UI before installing the exact newly qualified APK; modal/ANR or boot failures are recorded failures, never suppressed into a pass. Bound startup and absent-device ADB collection. Normal shutdown uses ordinary guest `sync`, then emulator shutdown; persistence is not inferred solely from a successful command return.
