# Verification record: NOT ACCEPTED

This is a reviewable development baseline, not a parity, performance, account, or device acceptance claim.

## Observed results (2026-10-22 UTC)

- Official dependency download initially failed with HTTP 403. After explicit approval, the same official dependencies downloaded successfully; this is no longer the compiler blocker.
- Production Kotlin compilation and debug APK packaging succeeded. APK inspection confirmed minSdk 26, targetSdk 36, the distinct native application ID, Internet permission, and no broad installed-app visibility permission.
- One full Gradle test attempt produced **94 results: 90 passed, 4 class-initialization failures**. Three Robolectric classes could not create a lock under a read-only OS home; one selected SDK 36, which requires a newer test JVM. No UI assertions or screenshots passed in that attempt.
- Test-home/cache redirection is portable via Gradle's user-home cache, not a hardcoded cloud path. Android renderer tests explicitly select SDK 35. These corrections are being re-run; no pass is assumed before a terminal result.
- Later account security tests briefly had a missing import, now corrected. Earlier passing results are evidence for the earlier source only, not the entire newest tree.
- Independently, all **27 data/domain JUnit tests** passed with the cached compiler/dependencies, including the current mapping/session code. This is partial evidence, not an Android build replacement.
- One ordinary anonymous home request returned HTTP 567 through the current network path. Testing stopped without retry, alternate hosts, credentials, or writes. This cannot establish the business service's availability or live API compatibility.
- No real login, post, like, follow, message, image upload, device installation, or permission grant was performed.

## Latest test attempt

The latest debug APK packaged successfully (SHA-256 `a0728c9d6b99518c86d141d5804efa036dc1cdc25e0b5fb36c55c3fca1341423`). The same source executed **98 tests: 97 passed, 1 failed**. The remaining failure is a MockWebServer cancellation test's server-shutdown queue timeout; it is being made deterministic, not ignored. All account, local, social, navigation semantics, settings UI and selected-image tests passed in this run.

Both actual Compose renderer PNGs were produced via synchronous Android `View.draw(Canvas)` with Robolectric native graphics, SDK 35, 420dpi, portrait 393x852dp and landscape 852x393dp. Independent inspection confirms only the narrow component claims: compact 268x62dp dock, centered placement, icon above label, and clear/unclipped default labels. These are synthetic component renders, not device screenshots, full-screen safe-area proof, animation/FPS measurements, or live account evidence.

## Test coverage inventory

Synthetic tests cover source protocol vectors, route/cursor decoding, response bounds, cancellation, pinned account generations, cookie/proof origin restrictions, crypto envelope integrity/recovery, login state, local persistence/corruption, favorites/history/blacklists, local follow paging, safe cache paths, mutation uncertainty, and temporary image grants/staging. Tests use local fake transport or loopback servers, not production accounts.

Compose tests cover stacked labels, selected semantics, callback routing, and minimum 48dp click regions. A Robolectric native-graphics harness renders the actual Compose dock in portrait/landscape; these are component tests, not a device screenshot or full application acceptance gate. Full NativeApp synthetic renders, keyboard/inset checks, scroll-hide and retained-tab tests are being added separately.

## Reproduce

Use a full JDK 17 and Android SDK 36. Run `./gradlew :app:testDebugUnitTest` first for quick feedback, then `./gradlew :app:assembleDebug :app:lintDebug`. CI uses these same debug/synthetic checks, read-only repository permissions, and no release publishing or secrets. Generated reports are under `app/build/reports`, XML under `app/build/test-results`, and renderer output under `app/build/verification` when produced.

## Remaining acceptance gates

Final-source tests and lint; independent inspection of actual rendered PNGs; portrait/landscape/large-font/full-screen keyboard and safe-area evidence; real service reads; explicitly authorized interrupted account flows; real-device gesture/TalkBack/rotation checks; and measured frame timings. Native implementation alone does not establish lower jank. Keep the draft unmerged and do not claim full Flutter parity or release readiness.
