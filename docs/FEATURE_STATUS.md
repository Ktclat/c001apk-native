# Native implementation status and parity gaps

**NOT ACCEPTED for full Flutter functional/UI parity.** Recognizable source UI and working behavior are both required; neither source inspection nor synthetic tests establish live-service/device acceptance.

## Evidence snapshot

- Publication base: `0c1bec8211236aee248b233d33585b88f79e91ac`, verified by CI #5 with 310 tests. That evidence does not cover the newer source below. PR #1 remains draft/unmerged; `main` is unchanged.
- **Build54 coherent local gate: PASS.** 383 tests across 79 classes, zero failures/errors/skips; debug assembly and lint passed. Lint has zero errors, 37 warnings and one hint. All 271 frozen source hashes matched, with 189 fresh artifacts.
- Seven actual-root default-hide/recovery tests, five My routing fixtures covering 11 paths plus account-switch/guest guards, and two light/dark personal-list background tests passed. The background correction and earlier fresh frames were independently inspected; synthetic tests/frames are not whole-page parity, real-account proof or actual TalkBack validation.
- **Independent limited-publication review: PASS for this exact source.** Remote CI remains a separate gate. Exact local APK identity and signing limits are in [verification evidence](VERIFICATION.md).

## New source and its boundaries

- **Source-contract 403 corrections, unproven live:** source-format User-Agent/platform-profile injection and Home installation time in milliseconds. All existing device codes remain unchanged, including account-bound codes; only new-installation codes gain actual platform metadata. Build54’s four installation-time tests passed, including corrupt-value/idempotence coverage. No device-identity rotation, vendor attestation or live-server acceptance is claimed.
- **Actual My destinations:** source-structured local shortcuts, notices and authenticated navigation. Six typed routes now cover following, fans, recent visits, server collections, likes and replies, with five passing actual-root fixtures covering 11 destination paths and account-switch/guest guards. Build54 verifies the personal-list background correction. Their read-only generic cards and account-header presentation are not full source parity. Server avatar/statistics are missing; local data and fabricated counts do not stand in for server data.
- **Categorized Settings:** category navigation and a wide two-pane layout, with real appearance/font/privacy/data controls. Official device-code import, manual device/profile/protocol editors, image-cache clearing and emoji-art rendering remain gaps; explanatory text is not an implemented editor.
- **Visible cross-tab jelly:** an actually traveling elastic selection pill, fixed labels/icons/hit areas and compact bottom overlay in both orientations. Consumed-scroll hysteresis, fling continuation, resets and stable trailing clearance remain. Synthetic root frames establish the visible motion, not physical-device smoothness.
- **Supporting default-scroll behavior/diagnostics:** floating navigation hides on scrolling by default, with no separate policy picker; touch exploration no longer blanket-disables scroll hiding. Existing labeled toolbar/scroll nodes expose a dock-reveal accessibility action with eligibility and stale-owner checks. Local-only diagnostics remain; keyboard/root/search gates remain, and no system accessibility settings are changed. The coherent local gate passed; actual TalkBack/device validation remains open.

## Retained implementation and open parity work

- **Home/detail/text:** source-sized metadata, original Flutter glyphs/launcher, populated synthetic media, continuous detail/comments, reply sorting and text replies. Feed/reply image composing, actionable reply likes, full card/template presentation and original emoji-art rights/panel parity remain incomplete. The 12,000-character preview still needs a genuine integrated full-body path; staged full-body work is not parity.
- **Accounts/local data:** encrypted vault, generation-bound cookies/proofs, login/challenge UI, notification provenance and mutation ownership guards; typed social/chat operations, chat images/feed sharing, favorites/history/local follows and blacklist storage. No real-account write or authenticated end-to-end acceptance is claimed. SAF, persistence, recovery and process-death behavior still need device checks.
- **Platform/media:** gallery/GIF/zoom/save/share, isolated public browsing, native-client update checks, launcher-visible apps and explicit selected-app update checks. Full inventory, browser-history restoration, complete app-detail presentation and integrated native download transfer/completion remain incomplete or staged. A reviewed browser handoff is not equivalent to verified native transfer/completion.
- **Assets:** three pinned build-time Noto fonts, licenses/hash verification, fixed/dynamic colors and reading preferences. Separate prerequisites passed 31 offline font-preparer fixtures, offline verification of all three fonts and 20 native-provenance self-checks. Build54 also verified packaged font pins and font/icon license bytes. These counts are separate from the 383 Android/JUnit tests; standalone font binaries stay out of Git.

## Acceptance and historical warnings

The reported 369.230769×821.538462 dp/DPR3.25 mapping has synthetic coverage, not native-device validation. The older 400×890 dp/DPR3 fixture is hypothetical. Our software-emulator attempt did not complete boot or reach an app run. Real Xiaomi 15/HyperOS measurements, accessibility/performance acceptance and live 403 resolution remain unestablished. See [verification evidence](VERIFICATION.md).

Historical `76a8d573` APKs retain the notification-deletion warning; `2166613e` fixed that but preceded the pre-I/O generation-race correction. New source does not repair old installations. Debug certificates can differ between local and CI builds; no release signing or in-place upgrade continuity is promised.

The coherent local gate and independent limited-publication review passed. Remote CI remains a separate gate. Whole-app parity, live-service and physical-device acceptance remain separate open gates. No completion or merge is claimed.
