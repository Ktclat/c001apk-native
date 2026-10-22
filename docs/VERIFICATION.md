# Verification record: full parity NOT ACCEPTED

A working feature set and a recognizable Flutter UI are both required. Synthetic checks, source inspection and successful compilation do not establish live-account acceptance, device behavior or performance.

## Published baseline and current gate

The publication base is `0c1bec8211236aee248b233d33585b88f79e91ac`; CI #5 verified 310 tests on that published snapshot. Its prior exact-source gate and limited incremental-publication review do not automatically cover the newer local changes below. PR #1 remains draft/unmerged; `main` is unchanged.

- **Build54 coherent local gate: PASS in 14m21s.** **383 tests across 79 classes**, zero failures, errors or skips; `assembleDebug` and `lintDebug` passed. Lint reports **zero errors, 37 warnings and one hint**. All 271 frozen source hashes matched; the archive contains 189 fresh evidence artifacts.
- Coverage includes **seven actual-root default-hide/accessibility-recovery tests**, **five My routing fixtures covering all 11 destination paths plus account-switch/guest guards**, and **two background tests covering light/dark themes across five states**. Four installation-time tests cover corrupt-value/idempotence handling. The prior focused background correction and fresh collection/light/dark frames were independently inspected; these are synthetic checks, not real-account or actual TalkBack acceptance.
- **Separate prerequisites passed:** 31/31 offline font-preparer fixtures, offline verification of all three existing fonts, and 20/20 native-provenance self-checks. These are not added to the 383 Android/JUnit count. The APK's three font pins and bundled font/icon license bytes were also verified.
- **Independent limited-publication review: PASS for the exact Build54 source.** This local result is not itself a remote CI result, merge approval, or full functional/UI parity acceptance.

### Local debug APK identity

- Size: **17,811,587 bytes**.
- SHA-256: `e3a6239c9942af5af0a72978536c91ec64e94becbdba7f007d887ddbba125a84`.
- Local signer SHA-256: `f2840c7ab104085a0d42ca4773d0682db237b5eaea8b9b470d9ca792dc70a759`, distinct from the CI debug signer. This does not establish release signing or in-place upgrade continuity.

## Changes under verification

- **Source-contract corrections for the reported 403, unproven live:** a source-format User-Agent uses validated Android manufacturer/brand/model/build-display/version metadata. All existing device codes remain unchanged, including installation and account-bound codes; only new-installation codes gain actual platform metadata. This is a local client identifier, not hardware identity or vendor attestation. Home's stored installation time is normalized from seconds to milliseconds without rotating identity; the signature timestamp remains seconds. Source compatibility is not proof that a server will accept a request or that a reported 403 is fixed.
- **Actual My destinations:** the source-structured My page now routes six typed destinations: following, fans, recent visits, server collections, likes and replies. Their five actual-root fixtures passed in Build54, including 11 destination paths and account-switch/guest guards. The themed personal-list background correction also passed. Their projections are read-only generic cards; card and account-header presentation are not complete source parity. Server avatar/statistics remain missing; local collections do not substitute for server collections, and absent account counts are not fabricated zeros.
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

- The earlier anonymous Home probe returned HTTP 567 through the configured network path. The new profile/timestamp changes have not established a live 403 fix, general backend availability or a general outage. No real login, account write, upload, full inventory scan, installation or permission grant was performed for this verification.
- The software-emulator boot attempt did not complete; the app was not installed or run there. There are no emulator/device screenshots or real Xiaomi 15/HyperOS, TalkBack, system-gesture, keyboard, lifecycle, FPS or memory acceptance results.
- Feed/reply image composing, actionable reply likes, complete card/template presentation, original emoji-art rights/panel parity, full installed-app inventory, integrated native download transfer/completion and some Settings/My capabilities remain gaps. Staged work is not integrated acceptance.
- The 12,000-character body preview remains a genuine full-text gap; separate full-body work is not integrated. The older accepted restoration test preserved screenshots, visible text/IDs, actual list position and no reload, but its clipped first body was already complete: it did not reproduce the earlier pending-to-complete transition or prove every prior failure's cause.
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

The coherent local gate and independent limited-publication review passed. Remote CI remains a separate gate. Keep the draft unmerged. Whole-app functional/UI parity, authorized live-service validation and physical-device acceptance remain separate open gates.

The results-only documentation update followed the frozen Build54 run; production and test sources are unchanged.
