# Native independent About page: focused validation

## Scope and identity

Settings → 关于 now opens the independent `native-about` page, not local diagnostics. About retains a diagnostics row; Settings → 协议与设备 → 本地诊断 is unchanged. The existing update-settings page is linked without adding any update request or changing startup-update preferences.

The page uses the Android application label (`c001apk Native`), actual BuildConfig version name/code/build type (`0.1.0-dev`, `1`, `debug` in this gate), the existing launcher artwork, and exact converted Flutter Material glyphs. It uses the Native repository rather than presenting the Flutter repository as the Native project, and does not imply that the Native build is the Flutter binary. The five new vector outlines were independently compared with the pinned Flutter 3.24.5 MaterialIcons-Regular.otf (SHA-256 `d9865b671a09d683d13a863089d8825e0f61a37696ce5d7d448bc8023aa62453`).

No account, protocol, signing identity, installation, automatic networking, or automatic feedback behavior changes are included. External rows use the existing safe launcher only after an explicit click, with injectable callback tests and visible launch-error feedback.

## Fixed external destinations and packaged notices

The five destinations were checked by independent review on 2026-10-23, separately from the offline application tests:

- Native project: https://github.com/Ktclat/c001apk-native
- Native issue page: https://github.com/Ktclat/c001apk-native/issues
- Project AGPL text: https://github.com/Ktclat/c001apk-native/blob/main/LICENSE
- Flutter source: https://github.com/Ktclat/c001apk
- Source acknowledgements: https://github.com/Ktclat/c001apk-native/blob/aac8cf43362762d4e6763695e85bee2f71b9064f/THIRD_PARTY_NOTICES.md

Both repositories were readable and public; Native issues were enabled and readable, and main/LICENSE contained GNU AGPL v3. Main did not yet contain THIRD_PARTY_NOTICES.md, so the acknowledgement link uses the verified existing commit permalink (Git blob `43ffed36da3d789e6280f27893b5e818a35fcf9b`), not the broken main path. App tests intercepted click intents; they did not visit these URLs or submit feedback.

The offline page exposes exactly the eight existing files in `app/src/main/assets/licenses/`: Flutter-BSD.txt, FlutterLauncher-NOTICE.txt, FlutterMaterialIcons-CC-BY-4.0.txt, FlutterMaterialIcons-NOTICE.txt, NotoSansCJK-NOTICE.txt, NotoSansCJK-OFL.txt, PiliPlus-GPL-3.0.txt, and TextEmoticons-NOTICE.txt. These assets are unchanged. The UI explicitly calls them **already packaged licenses and source notices, not a complete dependency-license audit**, and distinguishes the project's AGPL from third-party licenses. This increment adds no new legal, artwork-ownership, or distribution-permission conclusion.

## Fresh local focused result

Candidate source tree: `80f449df0c9cb71c2d86d86d528a7e5b382f2cf2`, based on integration commit `aac8cf43362762d4e6763695e85bee2f71b9064f` / tree `2d48332bccb341a4575bcecc503f59044534da39`.

- v7 fresh gate: **16 tests / 5 suites passed**, zero failures/errors/skips; 16 matching START and END events; Gradle, runner and gate exits 0. The 15-minute watchdog did not fire.
- Suites: NativeAboutTest (4), NativeAboutRootTest (6), CategorizedSettingsTest (3), NativeDiagnosticsScreenTest (1), HomeDiagnosticsCopyRouteTest (2).
- All 387 frozen source/test files and the exact path set matched before and after. Frozen manifest SHA-256: `886113cfb658ce4addfbe0eb62c302620221cb294494daac66e673e68ace431f`.
- The local diagnostic build records synthetic local baseline commit `5c3ebf689931fb2469a2e36c5e4ab746846f06a9`, branch `master`, worktree `dirty`. That is truthful local provenance, **not** the remote integration commit, a published commit, or a clean remote checkout. Candidate tree and content hashes identify the tested files. Remote CI must independently establish its checkout tree and fresh APK provenance.
- Production fingerprint: `sha256:fb84eee431105a4acd3fa86b80a4de5a633343e34b03345175c51c620fe44c34`. Packaging adds only this validation document outside the production fingerprint; it does not alter the tested production or tests.
- Evidence: 28 actual Compose Activity-window captures under Robolectric native graphics, matching JSON sidecars, 90 raw TextLayoutResult records, and 8 exact asset-paragraph/boundary JSON records. These are **synthetic**, not physical-device screenshots, and are not Dialog captures.
- Current-source Settings → About → Diagnostics captures belong to this run. Separate older settings/diagnostics images are historical baseline only and are not combined into a purported old click sequence.

Only the focused local test task was run for this candidate. **Full test suite, lint, assemble, fresh APK identity/fingerprint inspection, and remote CI remain pending.** No fresh About APK or successful signing is established by this gate; no signing key was generated. Earlier baseline full-suite results do not validate this changed candidate.

## Runtime and readability coverage

Actual NativeApp tests cover Settings → About → Diagnostics → Back → About → Settings, the unchanged Protocol diagnostics entry, duplicate current-route suppression, inner reader/list Back handling, saved-state restoration of About/reader scroll state, and clearing a popped About route. Saved-state restoration is exercised by a restoration fixture, not claimed as physical rotation/device-process validation.

The matrix includes the reported 1200×2670 px host at DPR 3.25 (369.230769×821.538461 dp), drawing-top 140 px / gesture-top 179 px / bottom 0, 2× system font in dark portrait, and 2670×1200 px landscape at 2×. Actual configuration, effective TextLayoutResult font scale, raw supplied insets and measured window dimensions are recorded. New pages combine safe insets by per-edge union/max, not 140+179. Every tested actionable About row and Back target is at least 48 dp with full reachable touch bounds; scrolling reaches the tail.

Titles, subtitles, long URLs and selected notice bodies are checked with contiguous line coverage, no ellipsis, line/glyph bounds, text height and viewport clipping rather than the known synthetic hasVisualOverflow false-positive alone. Notice evidence checks the real first and last nonblank paragraphs of GPL at 1×, and GPL, OFL and TextEmoticons notice at 2×. Paragraph strings match the unchanged source assets, full selected-paragraph layout is checked, and the real first/final glyph and its line are scrolled into the viewport. A paragraph may be taller than the viewport. This is **first/last-paragraph layout and reachability evidence**, not a claim that every intermediate paragraph of every license was individually glyph-audited.

The only horizontal glyph-oracle exception is confined to exact packaged-notice paragraph checks: ASCII U+0020 in a line's `[visibleEnd, end)` trailing range. Vertical bounds remain checked. Internal spaces, other whitespace, visible glyphs and all About labels/URLs retain strict bounds. Diagnostic v6 proved the sole failure was GPL offset 102, an inkless trailing space with right advance 1098.2461 px against width 1096 px; preceding visible period 101 ended at 1077.9537 px. Raw screenshots/text layouts and independent examination of the actual pinned Noto U+0020 zero-contour glyph support this narrow correction. The original license text was not trimmed or rewritten.

Root-fixture account-transport counters cover only its injected AccountRuntime client. The separate existing update service is configured disabled before composition and its update action is not clicked; external rows are not clicked in that fixture. Separate callback tests cover explicit external intents without a browser. The counter is not presented as a universal transport monitor.

## Preserved failure history and limits

- v1: design-only; not executed.
- v2: deliberately stopped during compilation when independent review found the broken main acknowledgements link; zero cases/images, not a pass or test failure. All frozen bytes remained unchanged. A JDK attach diagnostic created an extra zero-byte `.attach_pid49`; the original strict path-set failure and exit 90 were retained, and the diagnostic marker was archived separately rather than globally ignored.
- v3: production compilation passed; test compilation failed on an invalid generic getApplication() call. Zero cases/images. Fixed only that test API call.
- v4: 12/16 passed; four fixtures failed while finding an uncomposed Settings lazy-list item, before the relevant About checks. Fixed precise lazy-container scrolling, not production code.
- v5: 15/16 passed; only the 2× GPL trailing-space advance assertion failed. About dark/landscape checks passed.
- v6: diagnostic-only 1/1 failure retained with raw glyph evidence and 4 images, acceptanceGate=false. It is not added to passing test counts.
- v7: the fresh 16/16 focused result above, with 28 images and unchanged production since the corrected v3 URL.

Existing Settings category-scroll state has a source-level carryover risk, but no reproduction is established here and no speculative fix is included. Full Flutter parity, live service/403 behavior, physical-device interaction, complete dependency compliance and release/signing continuity remain outside this increment's acceptance claim.

## CI34 retained failure and navigation-only regression repair

[CI34](https://github.com/Ktclat/c001apk-native/actions/runs/40001686347) used GitHub's PR merge checkout `8b4647c20bc8462579891ec13846bd6c94c21910` for About PR head `8fe1071aae629569b916bebf82c27d29e711beeb`. The checked source tree was `9d68b56648543dccc39f8dcc743b822f4f208f83`, identical to that PR head. Its full test result is retained as **843 cases / 133 suites, one failure, zero errors or skips**, not reclassified as flaky or a pass. The original artifact is 81,305,732 bytes, SHA-256 `8a19db5b5dac6583c2b8dd5f0978545e212e9d990497470d2fcdef10aae56dbd`.

The sole failure was `NativeAppDockPolicyIntegrationTest.defaultHideIsIndependentOfRealTouchExplorationListenerChanges`: old line 156 attempted to click `copy-native-diagnostics` immediately after Settings → About. About is now an independent destination, so that node was absent. The preceding real-pointer hide/reveal and touch-exploration steps had passed before this navigation failure; the failure does not establish a default-hide implementation regression.

The repair adds only the missing real click on `about-diagnostics` (构建与本地诊断) after opening About. The original pointer gestures, system listener changes, clipboard assertions, default-hide assertions, teardown and timeouts are unchanged. All other production and test files are unchanged. The distinct Settings → 协议与设备 → 本地诊断 coverage remains. A search of all test sources found no second About → immediate-copy assumption.

The fresh local focused gate ran the entire seven-case `NativeAppDockPolicyIntegrationTest` plus the five About/settings/diagnostics suites listed above: **23 cases / 6 suites, zero failures/errors/skips**, with exact matching START/END method-name sets and all successful terminal events. Gradle, runner and gate exits were 0. It retained 28 fresh About Activity PNGs and the additional dock-policy evidence; all are synthetic. The 900-second watchdog did not fire. No test retry was used.

Tested candidate tree: `39484e4987bc370c21e924ba984af0848042e34a`; frozen 388-file manifest SHA-256: `e3d11b7718db6db9d81b81b1f1e0ef50dcf5e7c4cd4fc7e7a95d0dab13e096b8`. The exact file/path set, bytes, index modes, base HEAD and candidate index tree matched before and after. The isolated shallow checkout contains the exact remote base commit object `8fe1071aae629569b916bebf82c27d29e711beeb`; local branch `local-ci34-fix` and worktree `dirty` truthfully identify its staged test change, not a newly published or clean remote checkout. Generated build metadata matched that HEAD, branch, worktree state and unchanged production fingerprint `sha256:fb84eee431105a4acd3fa86b80a4de5a633343e34b03345175c51c620fe44c34`.

This gate used the existing offline JDK 17 / Gradle 8.13 toolchain, one Gradle worker, no persistent daemon and previously prepared pinned fonts. Old test XML, reports, images, APK output and generated provenance were separated before execution. No signing key was generated, and no fresh APK, lint result, full-suite pass or device/live-service acceptance is claimed. This appendix is the sole post-test documentation edit. **A new remote full-suite/lint/assembly gate and exact-source artifact review remain required before integration.** PR #1/main, live 403, full Flutter parity and signing/update continuity remain outside this scoped repair.

## CI35 preserved lint failure and observed fixture state

[CI35](https://github.com/Ktclat/c001apk-native/actions/runs/40007768293) used GitHub PR merge checkout `f8664985c270a89a0f8e10e2666a88cd315911f2` for head `c3ccb61caea0f38b897de01dba0a252675d456da`; both have source tree `ad59fcfd816e8b8d6505f800f390bc39f0a96c60`. All **843 tests / 133 suites passed**, including the previously failing dock/listener/diagnostics case and all seven cases in that class. CI35 nevertheless **failed**: `lintDebug` reported one `StateFlowValueCalledInComposition` error at `NativeAboutRootTest.kt:127`, alongside 42 warnings and one hint. Its 81,123,998-byte artifact, SHA-256 `524fddd0310abec1938748dbee29aeee63c6ec9ae2ac27af765dd3be95735f0b`, and original failure remain separate evidence, not a full-gate pass.

The fixture read `store.state.value` directly inside `setContent`, which does not observe later StateFlow emissions. The minimal test-only repair declares `val accountState by store.state.collectAsState()` inside composition and passes `accountState` to `NativeApp`. It retains real state observation rather than freezing a snapshot. Production code, all assertions, original navigation fix, fixture networking guards and timeouts are unchanged; no lint suppression, baseline change or disabled check is included.

A fresh local combined gate now ran the same **23 tests / 6 suites plus the real `:app:lintDebug` task**. All 23 tests passed with zero failures/errors/skips and exact successful START/END sets. The newly generated lint XML reports **zero Error/Fatal issues, 27 warnings and one hint**. Gradle, runner and gate exits were 0; the 900-second watchdog did not fire. The fresh artifact contains 28 About Activity PNGs plus two dock-policy PNGs, all synthetic. No test or lint retry was used.

The offline local warning count is not interchangeable with CI35: the 15 additional CI warnings are exactly version/API-level advisories (`OldTargetApi` 1, `AndroidGradlePluginVersion` 1, `GradleDependency` 7, `NewerVersionAvailable` 6). All 27 local warnings and the hint match CI35 by issue ID/message, and there are no new local warning messages. This is an environment/report difference, **not a claim that 15 warnings were fixed**. No dependency or target-API change was made.

Tested tree: `ee9741ae78d2b115401cc8c6186d5f9b345a8667`. All 388 frozen source files and the exact path set, index modes, base HEAD and candidate index tree matched before and after; manifest SHA-256 `8438ea6507d03b5723299ce24e58b5415f0848fb2a3c1fd3865612ee832468f0`. The existing checkout/build cache was reused after clean restoration to exact remote base `c3ccb61caea0f38b897de01dba0a252675d456da`; generated provenance correctly records local branch `local-ci35-lint-fix`, dirty worktree and unchanged production fingerprint `sha256:fb84eee431105a4acd3fa86b80a4de5a633343e34b03345175c51c620fe44c34`. Prior tested source, freezes and run artifacts were preserved separately.

The gate retained offline JDK 17 / Gradle 8.13, `--no-daemon`, one worker and existing pinned fonts/signing guard. Old XML, reports, images, APK output and generated provenance were separated first. Same-run process observation found no remaining owned Gradle test executor or daemon at termination. No new signing key or fresh APK was created. This appendix is the only post-test documentation change. **The corrected candidate still requires a new remote full-suite/lint/assembly gate and exact-source APK/artifact review; this local 23-case gate is not a full-suite pass for it.** PR #1/main, live 403, device/IME/TalkBack behavior, complete Flutter parity and signing/update continuity remain outside this repair.
