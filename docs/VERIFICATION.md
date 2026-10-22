# Verification record: full parity NOT ACCEPTED

This is a development implementation, not completed Flutter functional/UI parity, live-account acceptance, or measured Android device performance. Both working features and a recognizable Flutter UI are required; a passing build alone is insufficient.

## Published snapshot and historical APK warnings

The publication base, `7efb97fbbf6281721379087b532a49a39961258b`, is the prior safety-only snapshot containing the baseline and two isolated safety backports. The review snapshot described below extends that base with broader UI work. Its UI and local test counts must **not** be attributed to the old base. PR #1 remains draft and unmerged; `main` is unchanged.

- Historical `76a8d57326cf14ebacd2b8eeda6d48b5c4fd661c` and its APK could expose notification deletion for a feed/reply entity in mixed lists. A logged-in user had to tap and confirm deletion; opening a list did not delete anything. No actual incident or server acceptance was observed. Do not use that old development APK for notification deletion/account operations.
- `2166613e5c59ec7d84485f6722833dca95b2b3e7` corrected notification deletion using entity type, exact endpoint/ID, repository provenance and captured account generation, with generic notification deletion failing closed. Its [CI run](https://github.com/Ktclat/c001apk-native/actions/runs/39758048025) passed build, lint and 103 tests. A subsequent review found a separate pre-I/O account-generation race in that snapshot: a delayed operation could capture a newly selected account. That historical APK must not be treated as current account-operation acceptance either. No actual cross-account send was observed.
- The current local source includes notification provenance, captured UI-generation checks before transport creation, and CAPTCHA callback ownership guards. All 15 generation-safety additions passed in Build46. These are deterministic synthetic checks, not real-account validation.

Publishing corrected source does not repair already installed historical APKs. All APKs are debug builds; local and CI certificates can differ. There is no release-signing or in-place-upgrade continuity claim. Do not uninstall to resolve a certificate conflict without preserving needed data.

## Latest local gate

**Build46: PASSED.** The exact-source full gate exited successfully in 12 minutes 48 seconds:

- **310/310 tests passed**, with zero failures, errors or skips. This includes 27 adaptive-root, 13 shortest-window, five short-header policy and five atomic Apps snapshot/Main-publication tests.
- Debug assembly passed. Lint completed with **zero errors, 36 warnings and one hint**.
- The source manifest was unchanged across the run; 168 fresh verification files were archived.
- Debug APK: **18,452,277 bytes**, SHA-256 `65342775ebafdf5bd8f63abd251cf99275b8e98d44faf0416cc2df7ef1681372`.

Independent review granted a **limited incremental-publication PASS for this exact source snapshot**, after checking all310 test results, source hashes, APK identity, fresh renders and remaining lint warnings. This is not a merge or whole-app acceptance approval. These local results are not a verified remote broad-UI CI result and do not establish whole-app parity, runtime/device acceptance or performance. The prior safety-only publication base must not inherit these UI/test claims.

Earlier diagnostics observed mixed Apps snapshots and worker-thread publication; those observations did not establish the cause of the earlier persistent readiness timeouts. Build46 verifies the applied atomic snapshot/Main-publication change and existing matrices, without claiming that every earlier failure's cause is proven.

## UI evidence and its scope

Actual Compose renders include source-faithful Home chrome, 35 dp avatar/author/time/device metadata, compact feed actions, continuous detail/comments and reply-sort controls, exact Flutter Material glyph paths, original adaptive launcher resources, and populated synthetic avatar/media pixels. Original Flutter production-widget captures support comparison. This is recognizable progress, not a pixel-perfect or complete full-app parity claim.

The floating navigation stays a compact bottom overlay in portrait and landscape. Consumed content movement uses 28 dp hide/20 dp reveal hysteresis, user-fling continuation, page/account resets, stable trailing clearance and reselect behavior. Classic mode may use a rail; floating mode does not silently change into one. Root/header scrolling adapts when fixed chrome would leave insufficient usable space, including 480×240 dp at font scale 2.0.

The Flutter device report identified an accessibility flag blocking forwarded scroll. Native currently guards auto-hiding using Android touch exploration; the same cause has not been established on a native device. A separate three-mode native scroll policy is staged only: 18 isolated tests passed, while two Compose tests remain unrun. That policy and its additional diagnostic support are not integrated into this review snapshot.

The passing Build46 matrices exercise real Home/feed/apps, online/local Following and My roots across portrait/landscape, narrow widths, font scales up to 2.0, synthetic bottom/side insets and IME, empty/short/error states, reachable end actions, direction changes, flings, reselect, semantics and stable hit bounds. Robolectric SDK35/native-graphics output is real Compose rendering, not an HTML mockup. Its synthetic system conditions are not real keyboard, gesture-navigation or HyperOS evidence.

The 400×890 dp/DPR3 case is a hypothetical aspect-ratio fixture, **not** validation of the reported device viewport of 369.23×821.54 dp at DPR3.25. It does not establish the user's actual display scaling, system insets or Xiaomi 15/HyperOS behavior. Runtime layout uses window size and insets, not model detection.

A software-emulator boot attempt did not finish. No app was installed or run on it, and no emulator/device screenshots were obtained. Deterministic animation frames and stable hit bounds do not establish FPS, smoothness, memory use or device performance; physical-device and TalkBack checks remain open.

## Text and asset evidence

Build46's restoration check passed: before/after screenshots were byte-identical, body/card bounds and visible post IDs were unchanged, the actual list index/offset remained `(1, 456)`, and the no-reload assertion passed. The first body's clipped bounds were 0×0 even though its card footer remained visible. Its body was already complete in this run, so this is **not** a direct reproduction of the earlier pending-to-complete mismatch.

The fixture compares all visible text, stable IDs and actual list state. Retained offscreen bodies stay in the evidence but are not compared as visible content. Any displayed pending body still fails. Bounds make this distinction reviewable. The implementation retains off-main parsing, immutable results, Main publication, parent-job cancellation and input-keyed state. Strict readiness/replacement/cancellation tests remain, without a completed-value cache or relaxed timeout to hide failures. Independent review accepted this bounded restoration contract and its exact evidence.

Build33's clean offline debug assembly passed with the pinned fonts prepared. The three APK OTF resources and bundled license texts were byte-compared to their pinned inputs. `app/tools/fetch_fonts.py` has 31 passing offline fixtures, fails on missing/corrupt offline inputs and is not a silent fallback. Font binaries are generated build inputs, not committed repository files.

## Remaining acceptance limits

- One ordinary anonymous Home request returned HTTP 567 through the configured network path. It stopped without retries, alternate hosts, credentials or writes. This does not establish backend availability or a general service outage.
- No real login, post, like, follow, message, upload, full device-inventory scan, installation or permission grant was performed.
- Feed/reply image composing, actionable reply likes, complete nested/template-card behavior, faithful Settings/My presentation and original emoji-art rights/panel parity remain unresolved. Chat image-selection/send code exists; that does not complete the feed/reply image composer.
- The decoder's explicit 12,000-character body preview remains a full-text gap. Separate full-body work is staged but not integrated; re-fetching the same bounded record is not full-body parity.
- Launcher-visible app listing and explicit selected-app update checks exist. Full installed-app inventory, complete typed app-detail/download presentation, confirmed native APK transfer and browser-history restoration remain incomplete or staged outside the integrated source. An explicitly confirmed browser handoff does not establish integrated native download transfer/completion. The staged native transfer uses a bounded anonymous client and SAF; acceptance does not require a particular Android download API.
- Local storage/SAF, WebView isolation, lifecycle/process-death, accessibility and performance still require device validation. Authorized live-service scenarios and independent whole-app functional/UI acceptance remain open.

## Reproduce from the repository root

Use JDK 17 or 21 and Android SDK 36. Prepare pinned fonts, then run their fixtures and the full Android gate:

```sh
python3 app/tools/fetch_fonts.py
python3 -m unittest discover -s app/tools -p 'test_fetch_fonts.py' -v
./gradlew :app:testDebugUnitTest :app:assembleDebug :app:lintDebug
```

For an already provisioned cache, `python3 app/tools/fetch_fonts.py --offline` verifies the font inputs without downloading; `./gradlew --offline :app:testDebugUnitTest :app:assembleDebug :app:lintDebug` also requires the Gradle distribution, SDK and dependencies to be cached. Gradle verifies font hashes and fails clearly if preparation is missing.

JUnit XML is under `app/build/test-results`, test/lint reports under `app/build/reports`, and synthetic images under `app/build/verification`. Compare reports and images from the same completed source snapshot; stale outputs do not prove a new run passed. CI uses read-only repository permissions, with no release publishing or secrets.

The exact-source full gate and independent limited incremental-publication review have passed. Keep the draft unmerged: this limited publication approval does not close the broader acceptance gates. Whole-app functional/UI review, authorized live-service validation and real-device accessibility/performance remain separate open gates. No full-parity completion or merge is claimed.
