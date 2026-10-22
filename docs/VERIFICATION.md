# Verification record: full parity NOT ACCEPTED

A working feature set and a recognizable Flutter UI are both required. Synthetic checks, source inspection and successful compilation do not establish live-account acceptance, device behavior or performance.

## Published baseline evidence

The publication base is `ad9ddf7a26afceb6682e9deb4ff82a66a86eed38`; its bounded ordinary-message reader passed Build66 and scoped independent publication review recorded below. Earlier gates and CI #6 are historical evidence, not acceptance of the new reader child-return increment. PR #1 remains draft/unmerged; `main` is unchanged.

- **Build54 coherent local gate: PASS in 14m21s.** **383 tests across 79 classes**, zero failures, errors or skips; `assembleDebug` and `lintDebug` passed. Lint reports **zero errors, 37 warnings and one hint**. All 271 frozen source hashes matched; the archive contains 189 fresh evidence artifacts.
- Coverage includes **seven actual-root default-hide/accessibility-recovery tests**, **five My routing fixtures covering all 11 destination paths plus account-switch/guest guards**, and **two background tests covering light/dark themes across five states**. Four installation-time tests cover corrupt-value/idempotence handling. The prior focused background correction and fresh collection/light/dark frames were independently inspected; these are synthetic checks, not real-account or actual TalkBack acceptance.
- **Separate prerequisites passed:** 31/31 offline font-preparer fixtures, offline verification of all three existing fonts, and 20/20 native-provenance self-checks. These are not added to the 383 Android/JUnit count. The APK's three font pins and bundled font/icon license bytes were also verified.
- **Independent limited-publication review: PASS for the exact Build54 source.** This local result is not itself a remote CI result, merge approval, or full functional/UI parity acceptance.

### Historical Build54 local debug APK identity

This is an earlier baseline APK, not an APK for the reader child-return increment.

- Size: **17,811,587 bytes**.
- SHA-256: `e3a6239c9942af5af0a72978536c91ec64e94becbdba7f007d887ddbba125a84`.
- Local signer SHA-256: `f2840c7ab104085a0d42ca4773d0682db237b5eaea8b9b470d9ca792dc70a759`, distinct from the CI debug signer. This does not establish release signing or in-place upgrade continuity.

## Published guest-session and own-profile-header evidence

- **Build55 focused PASS:** 154 guest/account/transport regression tests, zero failures/errors/skips, including 15 new guest-session fixtures plus account/data/ownership/CAPTCHA regressions. No assembly or lint was run.
- **Build56 focused PASS:** 62 tests, zero failures/errors/skips, including 15 profile tests and six actual-root My tests; 279 source-file hashes matched. No assembly or lint was run. **Its populated header was rejected for excessive vertical density:** passing functional tests did not establish recognizable Flutter presentation.
- **Build58 focused PASS:** 66/66 tests, zero failures/errors/skips; all 279 source hashes matched and 29 fresh artifacts were captured. No assembly or lint was run. Independent comparison resolved the scoped density blocker: at matching 393 dp width, the first notice starts near 338 dp versus Flutter’s 325 dp (previously 496 dp); statistics/grid spans are about 60.6/109.7 dp versus Flutter’s 59/108 dp. Dark and unknown-data states and the final action above the dock were checked.
- The reported-viewport frame is **1200×2670 pixels at DPR3.25**, yielding **369.23077×821.53845 logical dp**. The compact frame is **320×480 dp at font scale 1.6 and DPR1**: comparison with Flutter at DPR3.25 is logical-size only, not pixel-identical rendering. Both use synthetic insets and are not native-device measurements. The mixed interaction check combines a real swipe, programmatic absolute-end positioning, guarded dock reveal and an actual request through the synthetic destination fixture. It is not an all-gesture flow, a live request or physical-device proof.
- **Build59 coherent local gate: PASS in 13m27s.** **418 tests across 84 classes**, zero failures/errors/skips; `assembleDebug` and `lintDebug` passed. Lint reports **zero errors, 37 warnings and one hint**. All **279 frozen source hashes matched**, with **197 fresh artifacts** archived. All three packaged font pins and four font/icon license files matched. Focused runs overlap; their counts are not added to this total.
- **Independent limited-publication review: PASS for exact Build59.** The reviewer verified the 418-test XML, APK/signature/local certificate, matching source hashes, font/license bytes and lint findings. Seven fresh Build59 frames were byte-identical to the reviewed Build58 frames. New increments require their own remote CI. This scoped approval does not establish merge approval, full parity, live 403 resolution or real-account/device acceptance.

### Historical Build59 local debug APK identity

- Size: **18,105,866 bytes**.
- SHA-256: `a9f9ac57df94d2091447b9d1e2603e5fb1e4cab59bac6ce9c60cba77b3822742`.
- **APK Signature Scheme v2 verified**; local signer SHA-256: `f2840c7ab104085a0d42ca4773d0682db237b5eaea8b9b470d9ca792dc70a759`. This debug signer remains distinct from CI; signature verification does not promise release signing, old-version vault compatibility or in-place upgrade continuity.

The published guest/header source stores an encrypted guest `SESSID` separately from authenticated account jars. It is not established as the cause or cure of the reported 403. The own-profile GET belongs to the captured account and generation; it supplies server avatar, nullable feed/follow/fan counts, level and available experience progress, with an actual FEED stat route extending the earlier My destinations. Missing counts remain unavailable rather than fabricated zeros. Real-service/account acceptance, generic personal-card parity, header caching and unread-count behavior remain unverified or incomplete.

**Vault compatibility warning:** the published guest vault writes schema v2 and reads v1/v2, including older omitted-default v1 records. An older strict v1 reader rejects v2 and fails closed. A v2 vault requires a compatible newer client; do not promise loss-free rollback to an old reader or advise a silent reset. This is synthetic compatibility evidence, not an actual user-vault migration. Certificate compatibility is a separate restriction.

## Published reply-like evidence

- **Implemented scope:** explicit like/unlike in detail comments, reply lists and reply threads. Unknown like state disables mutation; guest taps open login without writing. Pending submissions reject duplicate taps, and captured account-generation plus mutation/CAPTCHA ownership checks remain. Only an acknowledged mutation triggers the reply refresh; unconfirmed/failed outcomes are not displayed as successful writes.
- **Earlier focused failures:** Build60 ran 90 tests with six root-fixture wait failures. Build61 diagnostics observed the correct POST and a queued UI refresh before semantics synchronization; this was not a passing gate. Build62 changes the wait to require the visible flipped state while retaining all original request/read counters and method/path/query/form assertions, rather than accepting counters alone or weakening assertions.
- **Build62 focused PASS:** all **10 `NativeReplyRouteRequestTest` fixtures**, zero failures/errors/skips, with **283 matching source hashes**. They exercise both like/unlike directions on all three actual NativeApp routes plus unknown-state, pending-duplicate, stale-account and guest-login guards. Requests are intercepted without sockets; visible acknowledged refresh is verified. No assembly/lint or fresh screenshots were produced by this focused gate.
- **Build63 coherent local gate: PASS in 14m53s.** **444 tests across 87 classes**, zero failures/errors/skips; `assembleDebug` and `lintDebug` passed. All **283 frozen source files matched**, with **197 fresh artifacts** archived. Overlapping focused counts are not added to this total.
- **Lint: zero errors, 37 warnings and one hint.** Fresh debug, unit-test and Android-test lint analysis completed. `lintReportDebug` was legitimately UP-TO-DATE; the archived report timestamps and reuse provenance are recorded rather than described as newly generated reports.
- **Independent limited-publication review: PASS for exact Build63.** This is published-baseline evidence, not acceptance of the newer full-body source. No real provider/account/device acceptance, full-parity approval or live 403 resolution is claimed.

### Historical Build63 local debug APK identity

- Size: **18,000,783 bytes**.
- SHA-256: `47adc8dd8ccfb2eaa642c757fa5c540ad2fb10977362fbb2b61b87bcebc185a7`.
- **APK Signature Scheme v2 verified**; local signer SHA-256: `f2840c7ab104085a0d42ca4773d0682db237b5eaea8b9b470d9ca792dc70a759`, distinct from CI. All three packaged font pins and four font/icon license pins matched unchanged. This remains a debug APK, not stable release signing or promised upgrade/downgrade continuity.

## Published bounded full-body evidence

- **Ordinary-message scope:** the actual `/v6/feed/detail` response `message` is retained before preview truncation, separately from the existing 12,000 UTF-16-unit list/history/favorites projection. Both raw and decoded detail text are bounded at **262,144 UTF-16 units**, not bytes, code points or grapheme clusters. Missing/invalid messages and nonempty structured-article output are not promoted into a supposedly complete ordinary body.
- **Reader and resource bounds:** parsing, normalization, links and page preparation run off Main; guarded publication occurs on Main. At most one **4,000-unit segment** is laid out at a time, without splitting surrogate pairs. User page changes reveal the new page's start. Shared JSON preflight limits depth to **64** and lexical nodes, including keys, to **20,000**, within the existing **4 MiB actual-response-byte cap**. Unsupported/error/limit states are visible, with an explicit browser-original action. These are bounded synthetic checks, not a global memory cap or device-performance result.
- **Captured ordinary read:** the request session is captured once and used by a fixed-supplier read clone. AccountStore is unchanged; generation/current-owner checks and guarded publication reject stale account results. Successful guest/authenticated Set-Cookie responses remain allowed under existing ordinary-browse semantics. Same-generation cookie revision changes do not by themselves invalidate the body; stale cookie writes still face the existing independent revision check. This is not a new revision-current read contract or permission to adopt another account's session.
- **Build64 focused result:** 275 tests, **273 passed and two failed**, zero errors/skips. The failures concerned a supplemental root-dialog renderer and lifecycle raw-counter fixture synchronization. Their corrections were test-only, not production changes.
- **Build65 focused PASS:** **11/11 tests across two classes**, zero failures/errors/skips; **294 source hashes matched**, with **four fresh actual-root PNGs** covering first page, final tail, unsupported article and browser error. Independent review agrees with this scoped corrected result. Neither focused run included assembly/lint; overlapping counts are not aggregated.
- **Build66 restoration boundary:** parent list → detail → Back is tested, including unchanged parent items/visible positions/scroll without reload. Detail → child → Back reader retention was still missing in that baseline; the later bounded increment below addresses it separately. No durable process-restoration acceptance is claimed. Structured article blocks, inline media and continuous full-text selection remain gaps.
- **Build66 coherent local gate: PASS in 9m29s.** **484 tests across 94 classes**, zero failures/errors/skips; `assembleDebug` and `lintDebug` passed. All **294 frozen source hashes matched**, with **201 fresh artifacts** archived. These are the coherent suite totals; overlapping focused counts are not added.
- **Lint: zero errors, 37 warnings and one hint.** Fresh app, unit-test and Android-test analyses completed; `lintReportDebug` legitimately reused UP-TO-DATE outputs. Their timestamps and reuse provenance are archived, rather than described as newly generated reports.
- **Independent limited-publication review: PASS for exact Build66.** Review verified all 94 XML files/484 tests, 294 matching source hashes, the APK, unchanged fonts/licenses and lint categories. Four fresh reader frames are byte-identical to the reviewed Build65 frames. This is published-baseline evidence, not child-return increment acceptance. No live provider/account/device or HTTP 403 acceptance, complete restoration or full Flutter parity is claimed.

### Historical Build66 local debug APK identity

- Size: **18,000,783 bytes**.
- SHA-256: `7283f76e46c098c84509a25a95a52d5b3c33209471e6e584f9b16a1022fef172`.
- **APK Signature Scheme v2 verified**; local signer SHA-256: `f2840c7ab104085a0d42ca4773d0682db237b5eaea8b9b470d9ca792dc70a759`, distinct from CI. The three font pins and four font/icon license pins match Build59; all three fonts and all seven bundled license files are also byte-identical to Build63. This is a debug artifact, not stable release signing or promised upgrade/downgrade continuity.

## Reader child-return retention: current gate

- **Bounded contract:** at most **13 memory-only, latest-per-feed bookmarks**, scoped by account/generation, feed and a digest of the exact raw UTF-16 body. Parsed readiness and identity validation precede the first restored measurement, rather than measuring a short pending placeholder that clamps scroll. Stable reply anchors and reply-filter checks guard row restoration. Repeated routes use the latest position for each feed; navigation/history is not rewritten. Restore/commit validation rechecks the current owner, cached body and route, rejecting stale, abandoned or departed work rather than reinserting an obsolete bookmark.
- **Retained versus reset:** actual-root child → Back tests retain the selected segment, positive scroll and page/footer geometry without corrective scrolling after Back. Equal-body metadata refresh preserves position. Changed/unsupported body or ownership loss resets; an evicted body loses its bookmark and refetches genuinely on return. A fresh owner/process begins again after loading, even when its saved route returns to the feed. Synthetic saved-composition/fresh-owner tests are not device process-death validation.
- **Storage/geometry limits:** no body, response, ParsedPost or bitmap is stored in Android saved state or the small bookmark records. Valid feed IDs longer than **256 UTF-16 units** remain readable with an explicit no-retention fallback. Font, viewport and reply hierarchy/content changes have no exact pixel-geometry guarantee; missing/mismatching reply anchors reset to a safe verified-segment start. The strict 13-bookmark limit is not a hard global browse-cache or application-memory cap. Existing body, JSON, HTML/link and transport bounds are unchanged.
- **Build67 focused result:** 182 tests, **176 passed and six failed**, zero errors/skips. One failure exposed a real equal-value helper-key defect. Others involved fixture observation, lazy-list departure, cancellation-copy and history-error-root cases; these are not all classified as harmless flakiness.
- **Build68 focused PASS:** **48/48 tests across three classes**, zero failures/errors/skips; **299 source hashes matched**, with **13 fresh actual-root PNGs**. No assembly/lint ran, and overlapping focused counts are not aggregated. Independent review confirmed the focused XML and guest-return frame. The 13 fresh captures are not 13 visually accepted screens: only the oversized-feed-ID capture has an unexplained blank toolbar area; the unsupported-body-reset frame does show its toolbar. The long-ID topbar is unproven visual evidence, not an established production or renderer defect.
- **Build69 controlled interruption:** the coordinator stopped the run after about 14m36s without artifact progress for roughly ten minutes; terminal exit was 130. All 299 source hashes matched. Preserved XML/artifacts were explicitly stale or partial, not a fresh pass/failure verdict. The stall's cause remains unknown.
- **Build70 diagnostic PASS:** 27/27 adaptive-root tests on unchanged source, zero failures/errors/skips; no assembly/lint. External per-test logging aided observation, not a production correction. The later unsuccessful diagnostic attach supplied no thread dump or proven explanation and is not a fix. Focused counts are not added to the coherent suite.
- **Build71 coherent local gate: PASS in 11m46s.** **532 tests across 97 classes**, zero failures/errors/skips; `assembleDebug` and `lintDebug` passed. All **299 frozen source hashes matched**, with **214 fresh artifacts** archived. The full gate used external per-test logging, with no source change from the diagnostic run; success does not establish why Build69 stalled.
- **Lint: zero errors, 37 warnings and one hint.** Fresh app, unit-test and Android-test analyses completed. `lintReportDebug` legitimately reused UP-TO-DATE reports; archived timestamps and reuse provenance distinguish them from freshly generated reports.
- **Independent limited-publication review: PASS for exact Build71.** Review verified 97 XML files/532 tests, all 299 source hashes, APK/hash and included ReaderPositionStore, the signature/local certificate, three fonts/seven bundled licenses, lint and fresh frames. The long-ID toolbar visual limitation above remains. **Remote publication/CI are PENDING.** This does not establish live provider/account/device or HTTP 403 acceptance, durable process restoration or full Flutter parity.

### Build71 local debug APK identity

- Size: **18,000,783 bytes**.
- SHA-256: `a909a36eb9d16190052619b0c3c1c128edbca5bb1461e24ae09496b8b824cff8`.
- **APK Signature Scheme v2 verified**; local signer SHA-256: `f2840c7ab104085a0d42ca4773d0682db237b5eaea8b9b470d9ca792dc70a759`, distinct from CI. All three font pins and four font/icon license pins matched. This is a debug artifact, not stable release signing or promised upgrade/downgrade continuity.

## Changes under verification

- **Source-contract corrections for the reported 403, unproven live:** a source-format User-Agent uses validated Android manufacturer/brand/model/build-display/version metadata. All existing device codes remain unchanged, including installation and account-bound codes; only new-installation codes gain actual platform metadata. This is a local client identifier, not hardware identity or vendor attestation. Home's stored installation time is normalized from seconds to milliseconds without rotating identity; the signature timestamp remains seconds. Source compatibility is not proof that a server will accept a request or that a reported 403 is fixed.
- **Actual My destinations:** the source-structured My page now routes six typed destinations: following, fans, recent visits, server collections, likes and replies. Their five actual-root fixtures passed in Build54, including 11 destination paths and account-switch/guest guards. The themed personal-list background correction also passed. Their projections remain read-only generic cards. The published source adds server avatar/statistics and an own-feed route. Build58 resolved the scoped header-density blocker and Build59 passed the coherent local gate; header caching/unread counts and full card parity remain open; local collections do not substitute for server collections, and absent account counts are not fabricated zeros.
- **Categorized Settings:** category navigation, a wide two-pane layout and working appearance/font/privacy/data controls are implemented. Official device-code import, manual profile/protocol editors, image-cache clearing and emoji-art rendering remain explicit gaps rather than ineffective controls.
- **Visible cross-tab jelly:** the selected dock background pill travels between destinations with elastic deformation; icons, labels and hit areas stay fixed. Deterministic root animation frames demonstrate this visible change, not real-device smoothness.
- **Supporting default-scroll behavior/diagnostics:** floating navigation now hides on scrolling by default, with no separate policy picker; touch exploration no longer blanket-disables scroll hiding. A “显示底部导航” accessibility action is attached to existing labeled toolbar/scroll nodes; eligibility and scroll-owner identity are rechecked to reject stale callbacks. Root, keyboard and search visibility rules still apply. These source changes do not change Android accessibility settings, and actual TalkBack/device validation is pending. Diagnostics are allowlisted and local-only, with clipboard writing only on an explicit copy action; the source fingerprint is not an APK hash.

Archived Compose output includes actual Home/root animation phases, categorized Settings and guest/synthetic signed-in My. Fresh collection/background frames accompany the Surface correction; final independent review also inspected fresh dock-recovery and large-font Settings-tail frames. Earlier policy-picker frames describe an older source, not the latest UI. The native mapping of the Flutter report uses 1200×2670 pixels, DPR3.25 and 369.230769×821.538462 logical dp with injected insets. That is a **synthetic mapping, not native-device measurement or reproduction**. The older 400×890 dp/DPR3 fixture is hypothetical, not the reported device viewport. Deterministic animation frames are not FPS or smoothness evidence.

## Historical safety and signing warnings

- Historical `76a8d57326cf14ebacd2b8eeda6d48b5c4fd661c` and its APK could expose notification deletion for a feed/reply entity in mixed lists. Deletion required a logged-in user's tap and confirmation; simply opening lists did not delete anything. No actual incident or server acceptance was observed. Do not use that old development APK for notification deletion/account operations.
- `2166613e5c59ec7d84485f6722833dca95b2b3e7` corrected notification deletion but preceded the separately identified pre-I/O account-generation fix. A delayed operation could capture a newly selected account. No actual cross-account send was observed. That historical APK is not current account-operation acceptance.
- The later baseline/source includes exact notification provenance, captured UI-generation checks before transport creation and CAPTCHA callback ownership guards. Their deterministic tests do not amount to real-account validation. New source does not repair already installed historical APKs.

All APKs are debug builds. Local and CI signing certificates can differ; no stable release signing or in-place upgrade continuity is promised. Do not uninstall to resolve a certificate conflict without preserving needed data.

## Remaining evidence limits

- The earlier anonymous Home probe returned HTTP 567 through the configured network path. Neither the profile/timestamp changes nor the guest-session implementation establish a live 403 fix, general backend availability or a general outage. No real login, account write, upload, full inventory scan, installation or permission grant was performed for this verification.
- The software-emulator boot attempt did not complete; the app was not installed or run there. There are no emulator/device screenshots or real Xiaomi 15/HyperOS, TalkBack, system-gesture, keyboard, lifecycle, FPS or memory acceptance results.
- Feed/reply image composing, complete card/template presentation, original emoji-art rights/panel parity, full installed-app inventory, integrated native download transfer/completion and some Settings/My capabilities remain gaps. Staged work is not integrated acceptance.
- Ordinary-message reading passed its baseline gate. Bounded child-return retention passed Build71 and scoped independent review; remote publication/CI remain pending, while durable process restoration and exact geometry after font/viewport/reply changes remain outside the promise. Structured article/inline-media rendering and continuous full selection remain gaps. Older parent-list restoration evidence did not prove all later reader behaviors or every prior failure’s cause.
- Font binaries remain generated, ignored build inputs, not repository uploads. The passing preparation and packaged-byte checks establish pinned asset integrity, not typography or device-performance parity.

## Reproduce from the repository root

Use JDK 17 or 21 and Android SDK 36:

```sh
python3 app/tools/fetch_fonts.py
python3 -m unittest discover -s app/tools -p 'test_fetch_fonts.py' -v
./gradlew :app:testDebugUnitTest :app:assembleDebug :app:lintDebug
```

Use `python3 app/tools/fetch_fonts.py --offline` to verify already prepared fonts. An offline Gradle gate additionally needs its distribution, SDK and dependencies cached. Missing or corrupt pinned inputs fail explicitly.

JUnit XML lives under `app/build/test-results`, test/lint reports under `app/build/reports`, and synthetic frames under `app/build/verification`. Match all evidence to one completed source snapshot; stale files or focused checks are not a full pass. CI uses read-only repository permissions, without release publishing or secrets.

The published ordinary-message reader passed Build66. The child-return retention increment passed Build71 and exact-source limited-publication review; remote publication/CI remain pending. Keep the draft unmerged. Whole-app functional/UI parity, authorized live-service validation and physical-device acceptance remain separate open gates.

Build54, Build59, Build63 and Build66 hashes/APKs describe historical source snapshots, not the child-return retention increment. This results-only documentation delta does not change the frozen runtime source.
