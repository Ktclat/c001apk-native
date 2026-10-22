# c001apk Native

Android-native reimplementation of [Ktclat/c001apk](https://github.com/Ktclat/c001apk), in Kotlin and Jetpack Compose. **Work in progress: not yet Flutter feature parity.** It is an unofficial client, not affiliated with Coolapk.

## Build

JDK 17 or 21, Android SDK 36, and Gradle 8.13 are required. Python 3.9+ is required only for initial font preparation and its tests. Set `ANDROID_HOME`, or add `sdk.dir` to untracked `local.properties`.

```
python3 app/tools/fetch_fonts.py
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
```

### Pinned font build resources

The three Noto Sans CJK SC OTFs are build inputs, not Git binaries or runtime downloads. Preparation obtains the same unmodified Regular, Medium, and Bold files as the Flutter reference from [CodePlayer/webfont-noto commit 7af038d32866b387c64ef8e644abf5c303e48db6](https://github.com/CodePlayer/webfont-noto/tree/7af038d32866b387c64ef8e644abf5c303e48db6/dist/NotoSans/NotoSansCJKsc-hinted/subset/NotoSansCJKsc-hinted-standard). SHA-256 pins are in `app/tools/fonts.sha256`; the preparer also checks exact byte counts and the existing upstream Git-blob hashes. Android resource names remain `noto_sans_cjk_sc_regular`, `noto_sans_cjk_sc_medium`, and `noto_sans_cjk_sc_bold`.

- Run `python3 app/tools/fetch_fonts.py` once after a fresh checkout. It reuses valid files without network access and fetches only missing ones using certificate-verified HTTPS, no redirects, a 20-second socket timeout, a 180-second hard per-font download deadline, and exact byte limits. The bounded child writes only to the parent-owned open temporary descriptor, never to a destination path supplied on its command line. Directory symlinks and changed temporary entries are rejected. A complete verified temporary file is published atomically without overwriting an existing target. No third-party Python packages or Git executable are needed.
- Run `python3 app/tools/fetch_fonts.py --offline` to verify without any network access or file creation. An absent or altered font is an error. An existing invalid file is never silently replaced, even in online mode: investigate it, move it outside the resource directory yourself if appropriate, and rerun preparation. Do not change the checksum merely to make a failed download pass.
- Gradle's `:app:verifyBundledFonts` task checks the same SHA-256 manifest before `preBuild`. It never downloads fonts or needs Python. If preparation is missing or a file was changed, the build fails with the relevant file names and preparation command, rather than unresolved `R.font` references. After fonts, the Gradle distribution, Android SDK, and all dependencies are cached, `./gradlew --offline :app:assembleDebug :app:testDebugUnitTest :app:lintDebug` can run offline. Font preparation alone does not cache those other dependencies.
- Run `python3 -m unittest discover -s app/tools -p 'test_fetch_fonts.py' -v` for the network-free preparation tests. CI runs these tests and preparation before Android checks. Downloaded OTFs and transient download files are gitignored; keep the checksum manifest, preparer, tests, and license text in Git.

The APK still embeds exactly these three fonts for offline rendering. [The copyright/provenance notice](app/src/main/assets/licenses/NotoSansCJK-NOTICE.txt), [SIL OFL 1.1](app/src/main/assets/licenses/NotoSansCJK-OFL.txt), and [third-party notices](THIRD_PARTY_NOTICES.md) remain bundled and unchanged. This preparation step does not change app typography or its font selection behavior.

Minimum Android API 26 (Android 8.0), following the updated user scope. Native package `io.github.ktclat.c001apk.nativeapp` coexists with the Flutter app; private account data is **not** automatically migrated. No production account, cookies, signing credentials, or release publishing is configured.

## Current development snapshot

The publication base is `335953bb0b78a76fc7cad35b11a55f04fc02e044`. Its guest-session/profile-header source passed Build59's coherent local gate: 418 tests, debug assembly and lint, followed by scoped independent publication approval. PR #1 remains draft/unmerged. That evidence does not cover the newer reply-like increment below.

The published implementation includes source-format User-Agent/platform metadata, millisecond Home installation time, isolated encrypted guest `SESSID`, guarded own-profile loading, actual My destinations, categorized Settings and the traveling elastic dock pill. All existing device codes remain unchanged; only new-installation codes gain platform metadata. Default scroll hiding and guarded accessibility recovery have no separate policy picker. Live 403 resolution and actual TalkBack/device acceptance remain unverified; generic personal cards, header caching/unread counts and broader Flutter parity remain gaps.

The **reply-like increment** adds explicit like/unlike controls in detail comments, reply lists and reply threads. Unknown state disables writes; guests are sent only to login. Pending submissions reject duplicate taps, account-generation and CAPTCHA ownership guards remain, and an acknowledged mutation refreshes the displayed reply state. Build62 passed **10/10 actual NativeApp route/request fixtures** without live requests. **Build63 passed the coherent local gate: 444 tests across 87 classes, zero failures/errors/skips, successful debug assembly and lint with zero errors, 37 warnings and one hint.** All 283 frozen source files matched. **Independent limited-publication review: PASS for the exact Build63 source. Remote publication and candidate CI remain PENDING.** The local gate is not provider or device acceptance.

**Vault compatibility:** the published guest vault writes schema v2 and reads v1/v2. Older strict v1 readers reject the format and fail closed; opening a v2 vault requires a compatible newer client. Do not assume loss-free downgrade or reset the vault to make an old build open it. Local/CI debug signing compatibility remains a separate limitation. See the verification record for exact baseline artifacts and historical safety warnings.

## Scope and verification

See [current feature status](docs/FEATURE_STATUS.md), [verification evidence](docs/VERIFICATION.md), [migration stages](docs/MIGRATION.md), and [architecture decisions](docs/ARCHITECTURE.md). A compiled shell, synthetic API tests, and screenshots are not evidence of live endpoint access, account migration, hardware performance, or functional parity. Server access challenges must be displayed, never replaced with fake feed data.

## License

AGPL-3.0; see [LICENSE](LICENSE). API contracts and behavioral references derive from Ktclat/c001apk (Flutter), itself derived from TG-Twilight/c001apk. Source references are retained with ported components. Third-party Android libraries retain their respective licenses. Existing upstream LICENSE is unchanged.

Font preparation assumes a trusted checkout without concurrent mutation of its resource directories. The path checks are not a general sandbox against hostile concurrent filesystem writers.
