# Rebuilt local Android environment — 2026-10-28

These are **new recovery tools**, not recovered originals and not inherited test approval. The old Oct27 workspace, signing key, APK and full evidence archives are unavailable. Historical hashes identify those artifacts; they do not restore them.

## Scope and fixed inputs

- Eclipse Temurin JDK 17.0.21+9 (official Adoptium API/release).
- Gradle 8.13, matching the repository wrapper SHA-256.
- Google command-line tools 19.0, SDK platform 36 revision 2, build-tools 36.0.0, platform-tools 37.0.1.
- Robolectric instrumented SDK 26, 28, 29 and 35 JAR/POM pairs from Maven Central; fixed published SHA-512 and byte counts in `robolectric.lock.json`.
- Font preparation stays in the repository's pinned `app/tools/fetch_fonts.py`; do not substitute system fonts.

`downloads.lock.json` contains fixed official URLs and checksums. Android's published SHA-1 is checked together with the recorded SHA-256 obtained from that HTTPS-verified official archive. Downloads use normal certificate verification; no alternative mirror, TLS bypass or automatic retry. Partial downloads remain unpublished until checks pass. Existing mismatches are errors, not overwritten files. Extraction rejects unsafe ZIP paths/symlinks and uses Python's tar data filter. Python 3.12+ and curl are required.

The new download/extraction boundary has 10 network-free synthetic tests:

```bash
python3 -m unittest discover -s . -p 'test_bootstrap_tools.py' -v
```

These test archive checks, atomic publication, traversal/symlink rejection and preservation of existing mismatches. They are not application tests, emulator validation or independent approval of a full gate.

## Install in a separate local directory

The generated toolchain must be **outside the tracked source directory**. Example:

```bash
export NATIVE_TOOLCHAIN_ROOT=/absolute/task/path/toolchain
python3 bootstrap_downloads.py
python3 install_archives.py
source ./env.sh
sdkmanager --sdk_root="$ANDROID_HOME" --licenses
sdkmanager --sdk_root="$ANDROID_HOME" --list_installed
"$NATIVE_TOOLCHAIN_ROOT/gradle-8.13/bin/gradle" --version
python3 prepare_robolectric.py
```

Installation deliberately refuses to overwrite an existing JDK/SDK directory. The verified Gradle ZIP is also seeded in the normal wrapper cache; the project wrapper performs its own verification/extraction when first invoked. `prepare_robolectric.py` can verify/reuse existing pinned runtime files. Keep receipts under the selected toolchain root.

For each fresh execution, set the same `NATIVE_TOOLCHAIN_ROOT` and source `env.sh` again. Managed proxy ports can change between executions. The helper replaces stale Java proxy options with the currently provided proxy, retains the system trust store and does not modify OS/security/network settings. No proxy credentials are accepted by the helper. `adb-task.sh` supplies a task-local HOME only for ADB because ADB 37 otherwise tries to create an unwritable OS-home directory; it does not start a server until an explicitly requested ADB command requires one. Its generated keys are excluded from Git. Do not persist an environment dump.

Then prepare and verify fonts in each selected worktree:

```bash
python3 app/tools/fetch_fonts.py
python3 app/tools/fetch_fonts.py --offline
python3 -m unittest discover -s app/tools -p 'test_fetch_fonts.py' -v
```

The toolchain user directory isolates the **new QA-only debug signing context**. This does not recreate the lost signing key or guarantee overwrite compatibility with any installed APK. Never uninstall or clear user data to resolve a signature conflict. No keystore/private bytes are included here.

## Build coordination and pending gates

`NATIVE_HEAVY_LOCK` defaults to the toolchain parent's `heavy-gates.lock`. All Gradle builds and emulator runs must use that same exclusive lock; never overlap Gradle and software emulation. Preparation alone does not cache Android application dependencies and is not an offline-build claim.

After an exact source freeze and explicit gate grant, the intended split is:

1. Fresh single-use Gradle JVM, 1 worker, in-process Kotlin, 1536 MiB daemon: clean, assemble, compile all unit tests, lint.
2. A separate fresh 1024 MiB daemon, 1 worker: all unfiltered unit tests. The repository test worker's 2 GiB heap remains unchanged.
3. Source hashes before/after, font/provenance/emoji bytes, all runtime cache checks, event/XML reconciliation, APK CRC/signature/fingerprint and exact artifact archive.

**A full-gate supervisor is not supplied by this environment-preparation package.** The lost owned-process lifecycle/watchdog implementation must be newly implemented, independently reviewed and synthetically tested before a new qualified full gate. Do not treat a shell timeout or an available lock alone as proof that all owned descendants exited. Prior approval of the lost implementation does not approve replacement code.

## Persistence and exclusion policy

Publish these source helpers and fixed lock manifests to the authorized test branch. Do not commit installed binaries, Gradle/Maven caches, fonts, AVD state, debug keystores, ADB keys, account data or environment dumps. Keep the three font pins/licenses and the original emoticon manifest/assets in the application repository as its existing policy requires.

At each reviewed source freeze, preserve complete Git objects/source and identify the exact commit/tree. A second directory in the same workspace is **not** durable backup. CI artifacts can preserve reproducible test output under their stated retention window; private Library backup requires separate destination approval and has not been authorized here. A durable upload is only established after a returned artifact/version identity and independent read-back hash verification. New tests/screenshots must never be represented as restoration of missing historical evidence.
