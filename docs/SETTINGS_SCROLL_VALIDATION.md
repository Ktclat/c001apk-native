# Settings category scroll isolation: bounded local validation

Full feature/UI parity, physical-device behavior and main acceptance remain unapproved. This is a narrow fix for one reproduced category-switch problem, not a new settings feature.

## Change and source binding

- Base integration: `ae47c57a23ec6ad6fa9ae1aa91a75936354154c0`, tree `eff3740f0b7c97b15eae27b001edd62cf68fb001`.
- `SettingsScreen` now places the existing detail `LazyColumn` in `key(detail)`. A different category creates a fresh detail composition/list, matching the intent of Flutter's existing `ValueKey(_section)`. There is no corrective `scrollTo`, category-specific long-lived position cache, or change to settings content, accounts, callbacks, About/diagnostics routes or floating-navigation defaults.
- The tested 389-file candidate tree is `35b0683eb56061472e508bf8391dca332b6a5171`. The independently recomputed 198-input main-source fingerprint is `sha256:e55f4dc7af1a3ccb15c10ca6f3589b20619ca8fda78c737395529091b582dd2e`.
- The local checkout anchor `9fbd5068dc40f3e0f9047b77881cec43ebe877d9` is synthetic and is not the remote integration commit. Frame sidecars now obtain actual commit, branch, worktree, fingerprint and fingerprint scope from generated `NativeBuildInfo`; baseline tree/integration are labeled separately. The capture metadata change does not alter test setup, six case flows, selectors, scroll timing, assertions or text-geometry checks.
- This document is the only addition after the tested snapshot. It does not change runtime or test bytes, and it is outside the main-source fingerprint.

## Reproduced baseline and controls

`SettingsCategoryScrollRootTest` opens the actual `NativeApp` settings route with in-memory browse data, a fail-closed account HTTP client and native updates disabled. It scrolls Appearance to its actual tail, switches to Privacy, returns to Appearance, then repeats through Protocol. After each switch it captures and observes the detail before any detail-scroll correction. Only the category rail is scrolled to reveal a category. Back navigation is also checked.

The unchanged production baseline completed six cases in two suites: five passed and one failed, with no errors or skips. The failing case was the 900×369 dp short-wide window (2925×1200 px, density 3.25), with actual Android resource system font scale 2.0. In all four post-switch states, the detail retained a non-top position. Privacy's heading was absent and its first item was clipped at the top; Protocol and the final return to Appearance omitted both the expected heading and first item from the visible detail. Native-Canvas pixels and semantics/geometry agree. Framework scroll-axis values are not pixel distances.

The 900×600 dp, 1.0-font control passed. Shorter content can clamp the reused list back to zero, so that control alone would miss the short-window/large-font problem. The narrow reference also passed: 1200×2670 px, density 3.25, status top 140 px, gesture top 179 px, bottom 0, system font scale 2.0. That fixture uses the user's reported dimensions but is not a claim of testing their physical device. Narrow Back/reenter reaches each category at its top.

## Same-source focused checks and separate lint completion

The fixed candidate completed all six focused cases in two suites, with no failures, errors or skips. The three new actual-root cases cover wide ordinary font, wide short 2.0 font, and the reported narrow window; the three existing categorized-settings cases retain privacy, native-update and protocol-diagnostics callback coverage. Exact START/END event sets match the XML cases. Do not add the overlapping baseline and fixed-run counts into a larger suite total.

All 24 fresh synthetic PNGs and 15 observations were retained. All expected category headings and first items are fully visible at scroll-axis zero. The four formerly failing short-wide post-switch PNGs change; the other 20 PNGs are byte-identical to baseline. Line/glyph/height/complete-text checks use actual `TextLayoutResult` and verify density and effective/system font scale. Raw `hasVisualOverflow` is logged rather than used as a blanket substitute for geometry. No whitespace exception is needed for these short labels.

The original combined focused-plus-lint invocation **did not pass as a whole**: after all six tests passed, Gradle reported its daemon disappearing during lint analysis, before a fresh lint XML existed. Original Gradle and runner exit 1, raw log, exact daemon log and test/UI artifacts are preserved. The daemon log has no diagnostic establishing OOM, JVM crash, external kill or application failure; the cause remains undetermined. There was no manual stop or watchdog expiry.

An independently reviewed, separately authorized lint-only invocation then completed `:app:lintDebug` on the exact same 389-file freeze. It did not rerun the six tests (zero test events). Its fresh XML reports **zero Error/Fatal, 27 warnings and one hint**, with unchanged issue/message/relative-location multisets compared with the preceding offline lint baseline. No diagnostics were suppressed. Gradle, runner and gate exits are zero. The original combined-run exit 1 is not rewritten as a pass.

Both phases used offline Gradle 8.13, JDK 17, one worker, no persistent Gradle daemon, unchanged JVM settings and a fixed 900-second watchdog. Before/after source hashes match the same candidate. Each original runner's exact per-run marker ownership check found no remaining Gradle daemon/test-executor processes on termination. No new signing key, installation, real login or service HTTP action was performed.

## Reproduction and remaining gates

With the existing pinned fonts, SDK and dependencies prepared, run the three-case class and existing categorized-settings class using Gradle test filters, then run real `:app:lintDebug`. Reports are under `app/build/test-results/testDebugUnitTest` and `app/build/reports`; synthetic frames, sidecars, observations, semantics trees and text layouts are under `app/build/verification/settings-category-scroll`. Compare complete artifacts to a single frozen source identity; missing or reused report files are not a passing gate.

This local result is **focused tests plus a separately completed same-source lint stage**, not a successful single combined invocation or full-suite run. Ordinary full Android CI, fresh APK/provenance/signature review and remote integration review remain pending. The test adds three cases and one suite; no full-suite total is claimed here. Semantics scrolling is not a pointer-gesture test. Physical-device input/IME/TalkBack, rotation across the 840 dp breakpoint, all other window/font combinations, live-account/service behavior, APK delivery and whole-app Flutter parity are outside this bounded result.
