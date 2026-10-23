# Text-only composer emoticons: bounded validation

## Scope and source

This implementation-only increment adds **183 literal text tokens**, in the Flutter picker's exact three-group order: 常用 108, 牛牛 32, 酷币 43. It does **not** copy the 183 image assets, render emoji artwork, establish artwork redistribution rights, or claim full picker/UI parity.

- Native baseline: `d6e0966d4ab3b7aa4d591c7ecae6e3a69109ad09`, tree `4c136ee70ba8c3a2fe796256c7d5ceb357b7aa3e`, on `implementation/native-foundation`. The local reconstruction's different commit ID is not the remote baseline; its tree matches exactly.
- Flutter catalog/picker reference: `e7db2976dd3e712476f02284c31413d52b3e754a`, `lib/utils/emoji_util.dart` and `lib/pages/feed/reply/emoji_panel.dart`; `entries.skip(4)` and the three grouping predicates are preserved. Attribution and source-file hashes are in `app/src/main/assets/licenses/TextEmoticons-NOTICE.txt`.
- Frozen candidate v8: 378 source files; source-manifest SHA-256 `9e2d56d6476a9a155a74c3e16cfc651f807c553e32929ea4dce7196a4847cd9a`. Its 12-file patch SHA-256 is `4fff723f53b5470565da36c1a746ba47eb2ac4a2c340cd9ba01067006f6573d7`. This document is a delivery-only addition, not part of those frozen-source hashes. Relative to v7, only the image-ownership test teardown changes; all production source is unchanged.

## Implemented behavior

Insertion replaces either selection direction, expands a selected partial grapheme outward, and moves a caret inside a grapheme to its end without deleting it. Invalid offsets append. The resulting caret follows the complete token and explicit insertion clears IME composition. Surrogates, combining marks, flags, variation selectors and ZWJ clusters have focused coverage.

The existing **500-grapheme send limit** is unchanged: an insertion may retain an over-limit draft, but sending/preparation stays disabled. The separate **32,768 UTF-16-unit resource cap** rejects the whole over-cap edit without truncating the draft or losing its selection. Opening/using the picker does not send, upload or request a transport. Pending confirmation/mutation, stale account or route, and outstanding image selection block editing. Image ownership and ordered cleanup remain covered by bounded synthetic tests.

The picker is a real Compose `Dialog` with full-token buttons and a reachable Close control. It inherits effective text density and uses `safeDrawing.union(systemGestures)`, the per-edge maximum, not the sum. Intro, groups and tokens share bounded scrolling; no fixed device dimensions are introduced into production.

## Executed focused gate

The preserved v7 focused source manifest is `9f650a2e2932c85d2fdc4f85986c1261bf855dc6156fd5db22cb290226581e01`. Its focused run completed on 2026-10-23: **74 tests in eight XML suites; zero failures, errors or skips**, Gradle exit 0, reported build duration 4m 26s. All 378 source hashes matched before and after. It covers insertion/catalog, image ownership, composer state/limits/rotation/route lifecycle and actual-root composer route/presentation. This is **not** a full-suite, lint or assembly pass.

Fresh evidence contains **49 PNGs**, including **16 captures of the actual showing Dialog decor**: four unscrolled first-open frames and all three groups across four geometries. Other images cover composer entry, inserted drafts and existing composer regression cases. Capture metrics include source fingerprint `sha256:1194fa5323d08901d44f741cd016145f46f75fd8e8916a15b97f55c1cef3a3a5` and actual text density/effective scale. This main-source-whitelist fingerprint is not a full repository or APK hash.

The exact synthetic host fixture is **1200×2670 pixels, DPR 3.25**, with separately supplied status top 140 px, system-gesture top 179 px and safe bottom 0 px. Every inset event is supplied those fixed values; logs distinguish raw platform dispatch, values supplied to the default handler, and default-handler return. Raw events can contain zeros. The installed test listener is restored on cleanup. These are controlled fixture inputs, **not real-phone system measurements**.

The first-open Dialog is 1199×2672 px; group 1 content-wraps to 1199×2666 px, while groups 0/2 are 1199×2672 px. Do not treat every Dialog as fixed-height or relabel the host as a Dialog capture. Full control targets must clear the 179 px top exclusion; the first-open Close target starts at 283 px. Checks retain actual line/glyph bounds, visible height, absence of ellipsis and clipping. A known generic Compose title-width overflow flag is not used alone as a clipping verdict; its raw value remains in diagnostics.

## Full-run interruption and bounded teardown correction

The initial full runner stopped during its signing-guard initialization because that guard also ran in the separate buildSrc build, which has no `:app`; zero app tests/lint/assembly ran. The guard was corrected to skip only builds without `:app`, retaining fail-closed checking for the real app. No signing key was generated.

The subsequent v7 full-r2 was **manually aborted for diagnosis**, with execution-session exit 130, after **10 observed failures**, predominantly initial-mount `AppNotIdleException`. These are not complete suite totals: the aborted run produced no final JUnit count, lint or APK. It retained 137 partial PNGs and 378 unchanged source hashes. Neither unsuccessful attempt is a full pass.

A controlled six-case diagnostic used one normal test JVM per variant and the exact class order: existing GenerationEntryUiTest (1), ComposerEmoticonImageOwnershipTest (3), ComposerRouteLifecycleTest (2). All phases inside each variant shared the same SDK35/default-graphics test and Snapshot classloader; runtime identity numbers are not cross-process object identity. A retained the original teardown and finished **4 passed / 2 failed**, with both lifecycle tests timing out at initial `setContent`. B changed only one action: delivering already-due Main callbacks after abandonment with `shadowOf(Looper.getMainLooper()).idle()`, before resetting the test Main dispatcher. B finished **6 passed / zero failures, errors or skips**. Both had exactly six START/END events and 379 unchanged diagnostic-source hashes.

Public snapshot observers showed that the first two real model abandonments in A left pending changes with zero observed applications; in B, the same state objects were applied once and pending changes cleared after due-task delivery. The already-abandoned third case correctly remained a no-op. This supports the bounded cross-test teardown mechanism, not every possible cause of Compose idleness failures. V8 keeps only the test teardown correction, with `resetMain` in `finally`; all diagnostic suites, probes and observers remain outside the delivered source. No production workaround, timeout increase, per-test fork, private snapshot reset, forced notification flush, ignored failure or weakened route assertion is included. The diagnostic comparison did not itself satisfy the ordinary full gate; the separate full v8 result is recorded below.

## Executed v8 full local gate

The unchanged 378-file frozen v8 candidate completed the ordinary unfiltered `:app:testDebugUnitTest :app:lintDebug :app:assembleDebug` command on 2026-10-23. Gradle reported **BUILD SUCCESSFUL in 23m 13s**. The Gradle, gate and runner exits were all **0**, with no manual stop or watchdog termination. **833 tests in 131 JUnit XML suites passed, with zero failures, errors or skips**; all 833 START events matched 833 END events. This includes the original route and full-post tests that failed during the interrupted v7 run. Source hashes matched the frozen manifest before and after execution.

Lint completed with **zero Error/Fatal issues, 27 Warnings and one Hint**. This is not a warning-free result, and no reduction from an older report is claimed without an environment-matched comparison. Assembly produced a fresh 17,778,187-byte debug APK, SHA-256 `e1a6ac11bafdd11f445ce02bc4cce8a7720af20345a46afcc9124cb28e11bc6e`, using only the existing temporary debug key. This does not establish release signing or compatibility with an older installed package.

The isolated full-run artifact contains **903 hashed files**, including **418 fresh PNGs**, the **16 actual-Dialog captures**, JUnit and lint reports, the APK and newly generated source-provenance code. Its artifact-file-manifest SHA-256 is `a852b22982f9c9c364612e08515f485ba4384a87f6bc8f32adca07396ddd1e30`. The main-source fingerprint remains `sha256:1194fa5323d08901d44f741cd016145f46f75fd8e8916a15b97f55c1cef3a3a5`; it is not the full repository/APK hash. Prior outputs were moved out before running. The executed runner SHA-256 is `96e361dfba66a718d1cdd8fe770ade9efa4ca75c23da8d7c0c07be18116a4ed7`.

Delivery v9 changes only this validation document from the previously prepared delivery v8. It does not change the executed candidate, copy diagnostic probes into it, or rewrite the preserved unsuccessful runs. A local full pass is not remote CI, physical-device validation, production release or overall parity acceptance.

## Artifact and next gates

The existing ordinary Android workflow already runs `assembleDebug`, `testDebugUnitTest` and `lintDebug`, and uploads the APK, JUnit reports, lint reports and all `app/build/verification/` outputs. No workflow replacement or weakened test filter is introduced. The Dialog/geometry JSON files already carry generated main-source provenance; a future CI artifact must additionally be checked against its exact checkout commit/tree, candidate/base relationship, full source manifest, APK provenance and file hashes. A PR merge checkout must not be mislabeled as the PR head.

The full local test/lint/assembly gate above is complete. Independent final artifact/package review and source-bound remote CI remain separate publication gates. The runner preserved fresh outputs and frozen-source checks and refused to create a signing key. This pass does not retroactively turn earlier failed or interrupted diagnostic candidates into passes.

Real 403 resolution, real-account/login/send/upload, physical IME/rotation/process-death, actual-device system insets, TalkBack and FPS/performance remain unverified. No new signing key is authorized by this increment. Do not promise release-signing or in-place upgrade continuity. PR #1 remains draft; this increment targets only the implementation branch, not `main`, and does not establish overall native parity acceptance.
