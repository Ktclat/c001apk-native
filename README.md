# c001apk Native

## Recovery test branch — 2026-10-28: not accepted for release

This work-in-progress branch preserves recovered source fragments and newly
reconstructed media/emoji changes. It is **not** the missing previously reviewed
source tree or APK, and does not inherit that artifact's acceptance or signature.
New CI results are separate from actual Android UI acceptance; the reconstructed
14-scenario UI plan is not approved merely because its test sources compile.
Remote Coolapk image recovery remains unverified. The source-only CI retains
verification reports, not APKs or signing material. Only newly qualified changes
may be integrated into main; earlier dated reports below apply solely to the
exact source/artifact identities they name.

## Current development scope — 2026-10-27

The native client remains **in development, not full Flutter parity**. The historical checkpoints below retain dated descriptions and existing published references, not a guarantee that original local runtime archives remain available. The missing local runtime archives are not restored by this reconstruction; the historical sections are not the current feature list.

**This bounded increment received independent actual-UI approval on 2026-10-27.** The frozen runtime passed 1,454 unit tests and 28 distinct API26 emulator scenarios (30 passing invocations including two narrow repeats). [Independent review](docs/INDEPENDENT_UI_REVIEW_20261027.md) records exact APK/source hashes, actual screenshots and limits; [opt-in reproduction](docs/qa/REPRODUCE.md) provides a test-only overlay. This publication adds documentation/QA material only after the tested runtime freeze; the portable runtime manifest verifies source equivalence. Remote CI for the published snapshot is separate from this local acceptance record. Subsequent [test-only gate corrections](docs/CI58_TEST_COMPLETION.md) preserve the accepted runtime and passed a combined 1,457-test local gate; their fresh remote CI remains separate.

- Published through `e1e7d6cc`: quiet-dwell floating navigation; public image URL normalization; public user profile and following/fans lists; internal notification destinations; and owner-scoped human-verification continuation for supported notification reads. These do not establish live-service or physical-device acceptance.
- This increment restores already-decoded rich feed/reply/template content in personal lists, retains verified reads for ordinary Retry, and improves category-scoped blacklist management.
- Running-UI review also prompted corrections to Android 8–9 navigation scrims, appearance-sheet system icon polarity, and enlarged-font landscape confirmation width. Final integration and independent running-UI acceptance are recorded separately; source presence alone is not approval.
- Personal-list Back still refetches rather than preserving list scroll. Liker avatar/time and the reply-linked feed preview remain outside this increment. Unsupported content remains explicit; this is not a claim of complete card parity.
- Android 8 / API26 remains the native minimum. Real account/provider verification, physical-device behavior and stable signing remain open. Do not uninstall or clear existing app data to overcome a signature conflict.

See [personal rich cards](docs/PERSONAL_RICH_CARDS_PARITY.md), [notification verification](docs/NOTICE_READ_VERIFICATION.md), and the scoped validation records for exact source and evidence boundaries.

## Historical increment: Markdown and raw detail — 2026-10-25

[PR #15](https://github.com/Ktclat/c001apk-native/pull/15) was integrated into main as `4564943997e2065f92bd5b78e331bd3ffbae0153` after clean [CI47](https://github.com/Ktclat/c001apk-native/actions/runs/40181700101) and independent source, artifact and UI review. Post-merge [CI48](https://github.com/Ktclat/c001apk-native/actions/runs/40183283419) also passed: **950 tests / 144 suites, zero failures/errors/skips**, debug assembly, and lint with **44 warnings / one hint / zero errors**. The two additional warnings are CommonMark update advisories.

- Common Markdown detail text and the explicit **查看原文 / 格式化显示** menu now use bounded parsing and owner-scoped, independent reading positions. Original structured fields remain literal in raw view; no new Markdown image requests are made. This is not full GFM, preview, export or Flutter parity.
- Code tree: `528cfe00a598ad4e12a7246f3a220d1fe405bf12`; the 203-input build fingerprint is `3e2eb50e30dd1e5f90b9cb9ecf353e573aa1f753f788f425686bfedf03535f0a`. CI48 APK: 17,941,884 bytes; SHA-256 `dcec158dfe9cce88c4e6ea4a523349ddb22504a0a07176a404efec6c822879ec`. Three pinned fonts, nine license files, icon, signature and absence of observer instrumentation were verified.
- **Android 8 / API26 remains the native minimum.** The separate Flutter SDK migration's Android7 minimum does not apply to this native package. Physical-device/API26/TalkBack/IME/performance and live Home403 acceptance remain open. Debug signing still does not guarantee overwrite installation; retain existing app data on signature conflicts.
- The original local 950-test gate had one preview-readiness failure. Its cause remains unexplained; subsequent observation runs were non-reproductions. Clean CI47/48 justify this limited increment, **not a causal scheduler fix**. Full details and unsupported formats are in the Markdown parity record.

## Earlier main baseline — 2026-10-25

`main` now contains the tested Android-native development baseline, not only the license. It was fast-forwarded from `0886d0851065b2a869c2d200f32f3216fca9c551` to `49c225942e1a9c966304902f323c884c3345bd5c`, preserving all 43 development commits. [PR #1](https://github.com/Ktclat/c001apk-native/pull/1) is now merged. New work may be pushed directly after independent review and the applicable test/build gates; a new PR is not required for this personal repository.

- Baseline source tree: `6c4970ed155d4f1d43f32330058fcaa3e77eeda3`.
- [CI44](https://github.com/Ktclat/c001apk-native/actions/runs/40086463155) verified the identical tree: debug assembly, **917 tests across 142 suites, zero failures/errors/skips**, and lint with **zero errors, 42 warnings and one hint**. Its synthetic merge checkout was `c963dda9255c43e137c4670268e70bdac524428d`, not the foundation head SHA.
- [CI45](https://github.com/Ktclat/c001apk-native/actions/runs/40177566057) **passed on promoted main `49c2259`**: debug assembly, the same **917 tests / 142 suites with zero failures/errors/skips**, and lint with **zero errors, 42 warnings and one hint**. Independent artifact verification confirmed clean main provenance, the same 201-input source fingerprint, three pinned fonts, eight license files and the existing icon. Its APK SHA-256 is `93a2c008b1ab29a1024d0eac56a400b504108b7b522a94aad92b42718e7f8d82`; size is 17,831,700 bytes. This is synthetic/build verification, not a device or live-service pass.
- Included development work covers the Compose app and account/session safeguards, ordinary and structured readers, composer, gallery retry, restored Home icon grid, text-emoticon picker, offline About/licenses, categorized Settings, scoped unread counts, My refresh, and default scroll-linked floating navigation. This is a tested development baseline, **not complete Flutter functional/UI parity**.
- New Markdown/raw-source and other current local candidates are **not included** in this baseline. See [upstream sync scope](docs/UPSTREAM_SYNC.md) and the detailed verification history before attributing a feature or test result to a build.
- Home HTTP403/live-service acceptance, real-device/TalkBack/IME/performance, stable release signing and full parity remain open. Debug certificates can differ between builds; do not uninstall or clear user data merely to overcome a signature conflict. Do not claim imported upstream changes have fixed403.

Older checkpoints below describe the state at the time they were recorded. Statements such as “main unchanged,” “PR1 remains draft,” or “integration pending” in those historical records are **not the current branch status**. Failed or inconclusive attempts remain documented and are not retroactively passes.

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

## Historical development snapshots (superseded)

## Historical passive Home diagnostics: CI24 checkpoint

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
