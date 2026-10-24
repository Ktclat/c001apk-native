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

## Passive Home diagnostics: CI24 passed; final documentation gate pending

[Draft PR #7](https://github.com/Ktclat/c001apk-native/pull/7) at `376b65f9cb78d579cbabe643ef954c1587cc944d` adds a bounded, local-only request journal to the existing explicit diagnostics Copy action. [CI24](https://github.com/Ktclat/c001apk-native/actions/runs/39920063354) passed ordinary debug assembly, **723 tests across 118 XML suites**, zero failures/errors/skips, and lint with **zero errors, 42 warnings and one hint**. Independent source-bound artifact and five synthetic readability-frame reviews passed. Final documentation CI and guarded implementation integration remain pending; this is not a 403 fix, diagnostic APK delivery approval or main merge approval.

Composer PR #6 was separately integrated only into implementation as `95eddf65d3e9379822048b40ebefacf36e1dc493` after final CI22 review; post-integration CI23 passed. Main is unchanged. Live 403/upload, physical-device/IME/TalkBack, default composer coroutine-scope and full Flutter-parity acceptance remain open, as does CI17's intermittent reply-readiness risk.

See [current verification](docs/VERIFICATION.md), [local diagnostics scope](docs/LOCAL_DIAGNOSTICS.md) and [composer validation history](docs/IMAGE_COMPOSER_VALIDATION_PENDING.md) for exact evidence and limitations.

## Historical reader-status validation: CI14

The separate [draft PR #3](https://github.com/Ktclat/c001apk-native/pull/3) contains candidate `7ca367ecaa59a14888b0a213d1196b9a6dd4f57d`, based on implementation `7e60aee5159b187d8f11cdf23eb12864da2f6d41`. [CI #14](https://github.com/Ktclat/c001apk-native/actions/runs/39885805681) passed debug assembly, **645 tests across 106 XML suites**, zero failures/errors/skips, and lint with zero errors, 42 warnings and one hint. Independent artifact and bounded ordinary/article frame review passed. **Final docs CI and guarded integration are pending; PR #1/main merge, full parity, live403 and actual-device/TalkBack acceptance are not approved.**

Successful single-segment ordinary and article readers omit redundant progress/end narration. Explicit empty, pending, parse-limit/error and image-failure states remain; multiple segments retain concise position, previous/next controls, final-segment notices and page-start behavior. Actual-root single-tail, child Back, same-model return and metadata-refresh checks passed. Parser/media/session and retention implementations are unchanged. Build80's local zero-test watchdog stop remains a separate unsuccessful validation attempt, not retroactively a local pass.

## Historical structured-article validation

The baseline is `a616a61ec38e5c2879cc42ce894400749c9f9f80` (Build71/CI10). The separate [draft validation PR #2](https://github.com/Ktclat/c001apk-native/pull/2) contains candidate `1d23984d81c6f40feb27db711078e5d67850cb7a`, tree `bdfea8d62697f0edebc3fe021b995c7497ec0bb8`. [CI #11](https://github.com/Ktclat/c001apk-native/actions/runs/39881207962) passed debug assembly, synthetic tests and lint. Independent review verified **640 tests across 106 XML suites**, zero failures/errors/skips, including 108 tests in nine new article classes. Lint has zero errors, 42 warnings and one hint. Source/artifact binding, APK signature, fonts/licenses and six fresh article frames received scoped review. **After final CI12 and exact review, PR #2 was merged only into the implementation branch as `7e60aee5159b187d8f11cdf23eb12864da2f6d41`; post-integration CI13 passed. This is not full feature parity, live-service or device acceptance.** PR #1 remains draft/unmerged to main; main is unchanged.

The bounded article reader supports source-ordered cover/title and text, image and share-URL blocks. Unknown/malformed blocks fail explicitly as a whole, rather than silently disappearing. Arbitrary HTML inline images, animated article media, audio/video and continuous full-text selection remain outside this increment. Image fetching is anonymous, bounded and cancellation-aware; unsupported configured proxies fail visibly rather than being silently bypassed. Fixed image geometry, generation/session ownership and whole-article bookmark identity remain guarded.

The earlier Build66 publication base was `ad9ddf7a26afceb6682e9deb4ff82a66a86eed38`. Its bounded ordinary-message reader passed Build66's coherent local gate: 484 tests, debug assembly and lint, followed by scoped independent publication approval. PR #1 remains draft/unmerged. That evidence does not cover the reader child-return increment below.

The published implementation includes source-format User-Agent/platform metadata, millisecond Home installation time, isolated encrypted guest `SESSID`, guarded own-profile loading, My destinations, categorized Settings, the elastic dock pill and explicit reply like/unlike. All existing device codes remain unchanged; only new-installation codes gain platform metadata. Live 403 resolution and actual TalkBack/device acceptance remain unverified. Ordinary detail messages are bounded at 262,144 UTF-16 units and displayed in explicit 4,000-unit segments; the bounded structured-block candidate is described above; arbitrary HTML inline media and continuous full-text selection remain gaps.

The **reader child-return increment** retains the latest position per feed in at most **13 memory-only bookmarks**, keyed by account/generation, feed and exact UTF-16 body digest. Parsed readiness precedes the restored first measurement, and reply positions use stable anchors. Focused actual-root tests retain the segment, scroll and geometry after child → Back without corrective scrolling. Equal-body metadata refresh preserves position; changed/evicted/unsupported bodies or lost ownership reset safely. Fresh owners refetch and start over. The bookmark must still match the current owner, cached body and route; stale or departed work cannot restore it. No body, parsed text or bitmap is saved in Android saved state; this is not durable process restoration or an exact geometry guarantee after font, viewport or reply changes. Valid feed IDs longer than 256 UTF-16 units remain readable without a bookmark.

**Build71 passed the coherent local gate in 11m46s: 532 tests across 97 classes, zero failures/errors/skips, debug assembly and lint.** All 299 frozen source hashes matched; 214 fresh artifacts were archived. Lint has zero errors, 37 warnings and one hint. Exact-source independent limited-publication review passed; **published as a616a61e, with CI10 verified**. Earlier focused counts are not added to this total. Build69's interrupted run had no fresh verdict or proven stall cause. The long-ID safe-restart capture alone has an unexplained blank toolbar area, not an established production defect. See the verification record for diagnostic provenance and the current APK identity.

**Vault compatibility:** the published guest vault writes schema v2 and reads v1/v2. Older strict v1 readers reject the format and fail closed; opening a v2 vault requires a compatible newer client. Do not assume loss-free downgrade or reset the vault to make an old build open it. Local/CI debug signing compatibility remains a separate limitation. See the verification record for exact baseline artifacts and historical safety warnings.

## Scope and verification

See [current feature status](docs/FEATURE_STATUS.md), [verification evidence](docs/VERIFICATION.md), [migration stages](docs/MIGRATION.md), and [architecture decisions](docs/ARCHITECTURE.md). A compiled shell, synthetic API tests, and screenshots are not evidence of live endpoint access, account migration, hardware performance, or functional parity. Server access challenges must be displayed, never replaced with fake feed data.

## License

AGPL-3.0; see [LICENSE](LICENSE). API contracts and behavioral references derive from Ktclat/c001apk (Flutter), itself derived from TG-Twilight/c001apk. Source references are retained with ported components. Third-party Android libraries retain their respective licenses. Existing upstream LICENSE is unchanged.

Font preparation assumes a trusted checkout without concurrent mutation of its resource directories. The path checks are not a general sandbox against hostile concurrent filesystem writers.
