# Native feed/settings parity integration (local candidate)

This independent tree preserves the original native baseline `ab9c61abd2508d5943671835ce69e436f8b59238`.
Inputs:
* Feed/Home/linked routes `d480a3aa760198a4d794e0d32a21a067a2403285`, tree `5e62b68f5aef876ffd9896d0a816f00e3d77edb8`: focused 124/124. The final commit adds only result documentation after unchanged tested runtime inputs.
* Settings `8d6ff0bf6bc8ead5bd8c63a448d3e94230c19d91`, tree `c177c1bfa79b2d1ebc2e2f9f5cbbd52b61814adb`: focused 67/67 on runtime `bf0f213ce54ccb328475831c7b8ba063676bf9dc`. The later accepted commit changes only `docs/SETTINGS_PARITY_INCREMENT.md`.

## Explicit merge resolution

Only `ui/NativeApp.kt` has overlapping source edits. Retained all settings SaveableStateProvider/edit/follow callbacks, the real cache controls, native-update/installed-apps settings, and explicit local-user-follow UI. Retained all feed detail recovery, Home template dispatch, event dispatch, typed source-list tabs, cancellation and saved tab/scroll route state. The top-app-bar conflict combines the authenticated follow selector with the event-specific title and guest/local-follow label. Removed a duplicated equivalent source-tab retention calculation in favor of tested `retainedSourceListKeys`; retained keys remain bounded by the navigation history.

`AppContainer.kt` is byte-identical to the original baseline: neither accepted slice changes it. ImageGallery only receives the accepted settings cache-loader exposure. No device-identifier import follow-on, real credential data, export candidate or remote publish is included.

## Gate

The source is frozen before a combined offline font/provenance self-test, debug assembly, full JVM/Robolectric test suite and Android lint. Terminal evidence is archived outside this source tree; a pending or failed combined gate is not release acceptance. See the two slice documents for synthetic fixture scope and remaining gaps. No live-account success, physical-device/TalkBack verification, or full Flutter feature parity is claimed.

## Aggregate regression corrections

Full-v1 on `af82fd23` ended with a disappeared Gradle daemon and no final test XML; it is not a pass and OOM is not proven. Same-source full-v2 serialized assembly/lint and tests, completing 1074 tests with 5 failures. The aggregate exposed an over-broad malformed-refresh retention rule: preservation is now explicitly public-fallback-only; ordinary authoritative revisions keep the existing unavailable-state/bookmark-reset contract and its original actual-route tests. A new model test covers both cases. Three test fixture assumptions were corrected without weakening geometry: optional installed-app update entry disabled only in the old unscrollable-empty fixture, with a separate enabled/scrollable large-font route test; asynchronous local-history child Ready state awaited before Back, preserving exact parent scroll checks; final appearance-tail checks now target the added reading-preview final text, retaining switch state and absolute-end assertions. Both failed aggregate archives remain available externally. New source requires a fresh complete aggregate gate.

Retained public snapshots carry an explicit stale/unconfirmed notice while an account refresh is pending or rejected, including service unavailability; they are not represented as newly accepted account detail. The actual verification-rejection route asserts this notice, and the model regression preserves explicit HTTP-unavailability failure alongside the old public snapshot. Existing captured-session/route cancellation tests remain required.

Full-v3 completed 1076 tests with one remaining Settings fixture failure. Its timeout screenshot shows prior **synthetic** history rows from other suites, proving the assumed empty shared local store was not isolated (not a production missing-history failure). The Settings root fixture now injects its own validated in-memory local repository before composition, asserts the authored empty state, and keeps real settings persistence, actual history-child flow, saved-state restoration and exact parent scroll/geometry assertions. All production files remain byte-identical to full-v3 for this final test-isolation correction. A new full gate is required.
