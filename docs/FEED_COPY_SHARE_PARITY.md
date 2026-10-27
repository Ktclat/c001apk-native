# Feed Copy / Share — bounded native increment

## Reference and scope

Reference: Flutter `0d9ed002980d935c1c9351393e9f16784aa78903`, `feed_page.dart` menu around lines 831–876, `utils.dart` `getShareUrl` / `getShareText`, `share_links_test.dart`, and the plain-text cases in `html_compatibility_test.dart`. Initial native starting point: `99b722d`. The final candidate was rebased onto `f1242518adccbb19c73951546530210c38cccf98` (tree `28564aa5b0d69ceb5e1ba05cccfec79eeb5e2b19`), source-equivalent to published main `cd7f920d4ca4c94ede821cbcc3977cf168a681ad`; it preserves the profile/settings and preview-geometry correction. The previous increment’s API26 appearance-pointer regression is retained as a test-only addition, with no settings runtime change.

The native ordinary feed card's existing menu is reachable from lists and the detail reader. This increment wires **复制链接** and **分享** there. It does not add a duplicate detail toolbar menu, a direct recipient, server writes, moderation, reporting, or login requirements. Existing local favorite and like/login behavior remain separate. Structured posts use the same title/summary action but this is not a structured-article sharing/export implementation.

## Policy

- **Copy:** only `https://www.coolapk1s.com/feed/{encoded id}`. The previous unconnected label “复制内容” was misleading; this is not full-body copying.
- **Share:** plain title; if empty, plain message; if still empty, `动态`. Collapse whitespace to one line, retain at most **100 complete grapheme clusters**, then append `…` only when longer. Follow with the mirror URL, canonical `https://www.coolapk.com/feed/{encoded id}`, and `分享自c001apk`, each on its own line. The ordinary detail action uses its owned full body rather than its 12,000-unit list preview when available.
- Ordinary HTML uses Android's local parser, with layout-only block boundaries removed to match Flutter's DOM text walk; `<br>` and entities are retained as text, image placeholders/alt and link attributes are not exported. There is no fetch, HTML Intent payload, image transfer, or raw structured-source export. Arbitrary malformed HTML/HTML5-parser equivalence is not claimed.
- IDs must be raw ASCII-numeric native feed identities (1–4,096 units), never URLs, query strings, fragments, whitespace-padded IDs or percent-encoded paths. Both URLs encode the validated path component. Missing/invalid/oversized IDs offer no Copy/Share callback; existing favorite remains.
- Inputs above 262,144 UTF-16 units or 8,192 markup openings fail visibly, rather than cutting markup. A pathological 100-grapheme summary above 16,384 UTF-16 units also fails visibly; Copy link remains available. Android ICU supplies grapheme boundaries (the platform's Unicode version applies).

## Lifecycle and platform boundary

Payload preparation occurs only after a gesture, off Main for Share. A single-flight controller prevents pending duplicate actions. Menu callbacks close once, so a captured double-click cannot deliver twice. Source changes, account-generation changes, disposal, or lifecycle pause invalidate pending work; resume/recomposition never replays it. No text is saved to Android saved state or local storage by this feature.

The platform adapter receives strings only. Clipboard uses one `ClipData.newPlainText` item. Sharing launches `ACTION_SEND`, MIME `text/plain`, one `EXTRA_TEXT`, wrapped in the Android chooser. It sets no recipient/package, URI grants, stream, account state, credential, opaque request field or authenticated URL. Android may also migrate that text into a single plain-text `ClipData` during launch; tests inspect its exact text and absence of URI, Intent, HTML or grant flags. Chooser launch is not a claim that anything was sent. Missing/blocked platform services produce bounded in-app feedback with an explicit retry or Copy-link alternative.

## Verification — 2026-10-26

Tested runtime/source commit: **`c0433917eba4f2900e9948b80e91708771692443`**, tree **`11df85b1adbb59f8742693a593dfa8ad3d9b2652`**. Later verification-record edits are documentation only.

- **Full combined gate:** 1,260 tests across 189 non-empty XML suites; zero failures, errors or skips. Debug assembly, all test compilation and lint passed. All **513 tracked input hashes** were unchanged through the gate; it completed at **10:47:56 UTC**.
- **Build inputs:** three pinned fonts verified; all 31 font-preparer tests and 20 provenance self-checks passed. Main-source fingerprint: `sha256:dca3d41c808c86cb0ce7153bdd0d3f0eebb62bc5828d4f126dfab3877b6fe698`.
- **Focused gate:** 41 tests / 8 suites, zero failures/errors/skips. Payload cases cover title/message/fallback, exact URLs, markup attributes and image-alt exclusion, Flutter paragraph/br/entity rules, 100-grapheme limits (ZWJ emoji, combining marks, regional flags, including a minimum-API26 case), invalid/missing IDs, long attributes and oversized input. Controller/composable cases cover duplicate clicks, source/account change, pause/resume, disposal, cancellation, explicit retry and bounded error feedback. Actual NativeApp Home/detail paths exercise the menus; one case uses the production Android adapter, not the recording override, and verifies clipboard plus chooser Intent/ClipData end-to-end. No recipient is selected or executed.
- **Retained settings regression:** two API26 synthetic raw-pointer tests cover 12 left/center/right endpoint cases at font scales 1 and 2, including immediate down/up. They are included in the full total, not added to it again.
- **Lint:** zero errors, 41 warnings and one hint. Relative to the prior main gate’s 40 warnings, the net +1 is dependency-advisory variation only (+4 / −3: Compose BOM twice, serialization-json and CommonMark versus earlier DataStore, WebKit and MockWebServer notices). The Gradle dependency declarations and all non-dependency lint findings are unchanged; no suppression was added.
- **APK:** 18,441,981 bytes; SHA-256 `d20149e4899dfc281fbc0ca679702077c14505081b0d082c0298eca3de2a28c5`. Focused and full-gate APK bytes are identical and embed clean commit `c0433917…`.
- **Independent review:** source and the production platform-boundary test were accepted; all five original native-canvas frames were inspected individually and accepted for the bounded menu/feedback scope. The full-gate frame hashes match the focused originals exactly.

Evidence archive: `feed-copy-share-verification/full-c043391/` (summary, source identity, tracked hashes, XML, lint, APK, provenance and original frames); the earlier focused records remain alongside it. Frames render actual NativeApp/Compose activity and popup views with synthetic content—not fabricated mockups and not physical-device/system-chooser screenshots:

| Frame | SHA-256 |
| --- | --- |
| Home menu | `fd603b3460ce1350592969ae909abac3f8b8845e981961c042aaf3efe2938c43` |
| Home Copy feedback | `723b95bd3ebd29462b72240a5892b2eb74c5b609173eebdc339adf02cb9553a1` |
| Detail menu | `521280bcef605768cf4008e03394a5a55caef9097108dd3d8e7b59102d986c0e` |
| Detail menu, 2× font | `7aac7556806c28d091a2a4f64420c89ba437643a220eac5ea311773b0238b019` |
| Detail Share error | `d61eaff13f5622486f31d76bd76bf0425475845e6056df1f43b456c59e7910f4` |

### Unsuccessful attempts retained

The initial focused-build attempt was interrupted (exit130) to release the heavy lock for the preceding increment’s priority API26 tour; it is not a pass. The first completed focused run had **40 tests / one failure**: the test incorrectly expected `ClipData` to stay null after Android prepared the text-sharing Intent. The corrected test checks the exact platform-created single plain item and absence of URI/Intent/HTML/grants instead. The runtime adapter did not change to hide the failure; an additional real-root platform-boundary case was added. That failed run and its unchanged input hashes are preserved, not retroactively relabelled as green.

## Remaining boundaries

This work does not establish live-service or physical-device/system-chooser behavior, successful delivery to an external app, TalkBack/performance acceptance, arbitrary HTML5 equivalence, full-body Copy/export, moderation/report support, or complete Flutter parity. No external target was selected, new device-fixture infrastructure added, push performed by this implementation task, or user data cleared. Android8/API26 remains the minimum; existing signing/data-preservation cautions still apply. Publication/CI and artifact delivery remain a separate parent-owned gate.

## SDK35 launch-observation correction — 2026-10-27

The unfiltered local gate on `17fef120324a42395e257769e577ba9a3decb85b` completed with **1,456 tests / 215 suites / one failure**: `nativeAppMenuClicksReachRealAndroidClipboardAndChooserWithoutTransferOverride` observed null target `ClipData` at line 190. The preceding chooser, ACTION_SEND, MIME, exact EXTRA_TEXT and no-recipient/no-flags checks had passed. That original failure is preserved, not reclassified as a pass.

The [Android ACTION_SEND contract](https://developer.android.com/reference/android/content/Intent#ACTION_SEND) allows the caller to provide plain text through EXTRA_TEXT without setting ClipData itself, and documents migration during `Context.startActivity`. The [official text-sharing example](https://developer.android.com/develop/ui/compose/sharing/send#send-text-content) uses the same ACTION_SEND/type/EXTRA_TEXT/createChooser/startActivity sequence as this adapter. Thus ClipData is optional at construction, but post-launch normalization remains a valid expectation for this selected SDK35 path; production fields must not be added merely to satisfy an early observation.

Exact cached bytecode established the missing completion boundary:

- Robolectric 4.16 `ShadowInstrumentation.execStartActivity` queues the **original Intent reference** before delegating to real Instrumentation.
- SDK35 `createChooser` does not perform EXTRA_TEXT migration. Real `Instrumentation.execStartActivity` invokes non-blocking ActivityMonitors before `migrateExtraStreamToClipData`, which then recurses into the chooser target.
- Consequently, a queued chooser can already contain correct ACTION_SEND/type/EXTRA_TEXT while target ClipData is still null. Queue presence alone is not proof that `startActivity` returned. This establishes a legal interleaving, not a captured trace of the original failure.

Test-only candidate **`c1093464530c3ba45ffbb980805a4c70f428731b`**, based on combined integration `a96996ea6fba9646391dbb31719f84e308e03be8`, now waits for the app's existing share-success feedback, emitted only after `shareText` returns, within the **unchanged single five-second budget**. It then dequeues the actual chooser once. All original exact payload, non-null ClipData, single-item/MIME, no URI/Intent/HTML, no flags/data/package/component, single-launch and no-transfer-override assertions remain. No runtime or build configuration changed.

An additional deterministic, non-blocking ActivityMonitor test observes the same queued object before migration, asserts null ClipData there, returns null to continue the real SDK launch, then verifies normalized text after the actual adapter returns. It substitutes neither the adapter nor a launch result and uses no delay or enlarged timeout.

Focused verification on that clean frozen candidate passed **30 tests in five non-empty suites**, with zero failures/errors/skips (one additional LauncherResources XML contains zero tests). The new monitor probe and the formerly failing real NativeApp boundary test both passed. All tracked before/after hashes were identical; all **242** portable runtime/build-input hashes still matched the independently reviewed runtime. Exact JAR hashes, disassembly, preserved failure, focused logs/XML and source snapshots are archived in `native-share-intent-evidence/`. This focused result is not an unfiltered full-gate pass, a remote CI verdict, or new device/share-recipient acceptance; those retain their separate evidence requirements.
