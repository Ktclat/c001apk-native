# Reader-status validation history: scoped CI review passed, integration pending

This separate draft candidate is based on implementation commit `7e60aee5159b187d8f11cdf23eb12864da2f6d41`, tree `40022933a2bffc7ed3b60c3db8177a1cdb12ebb0`. The structured-article baseline already passed CI11/12 and scoped artifact/visual review. That evidence does not cover this new polish.

## Reader-status polish: CI14 and scoped review passed, integration pending

The separate [draft PR #3](https://github.com/Ktclat/c001apk-native/pull/3) contains candidate `7ca367ecaa59a14888b0a213d1196b9a6dd4f57d`, based on implementation `7e60aee5159b187d8f11cdf23eb12864da2f6d41`. [CI #14](https://github.com/Ktclat/c001apk-native/actions/runs/39885805681) passed debug assembly, **645 tests across 106 XML suites**, zero failures/errors/skips, and lint with zero errors, 42 warnings and one hint. Independent artifact and bounded ordinary/article frame review passed. **Final docs CI and guarded integration are pending; PR #1/main merge, full parity, live403 and actual-device/TalkBack acceptance are not approved.**

Successful single-segment ordinary and article readers omit redundant progress/end narration. Explicit empty, pending, parse-limit/error and image-failure states remain; multiple segments retain concise position, previous/next controls, final-segment notices and page-start behavior. Actual-root single-tail, child Back, same-model return and metadata-refresh checks passed. Parser/media/session and retention implementations are unchanged. Build80's local zero-test watchdog stop remains a separate unsuccessful validation attempt, not retroactively a local pass.

## Exact scope

Eight reviewed code/test files change only two runtime UI components and their six test classes. A successfully rendered single-segment ordinary body or structured article no longer shows permanent diagnostic-like progress and end narration. Empty ordinary text still has an explicit empty state; pending and error states remain visible. Multiple segments retain concise “第 N/M 段” status, previous/next controls, final-segment notices and page-start behavior. Links, media status/errors, parser/resource limits, session ownership and retention implementations are unchanged.

Tests use actual page tags and visible pending state rather than obsolete diagnostic copy as readiness signals. Added single-segment root cases cover compact large-text exact tails, child Back, same-model return and metadata refresh geometry. Existing multi-segment page-start and retention assertions remain. These assertions subsequently passed within the exact CI14 full gate and scoped independent review; that does not turn the interrupted local run into a pass.

## Build80: no runtime verdict

The first bounded local focused run selected the six changed test classes. Production Kotlin and Java compilation completed. Unit-test Kotlin compilation then reported no task/log progress for 300 seconds; the existing watchdog stopped the owned process at about 581.5 seconds elapsed. Its owning session returned exit code130. No tests completed, no fresh test XML or screenshots were produced, and all 315 frozen source/resource hashes remained unchanged. The attempted thread sample timed out. This does not establish OOM, a code failure, an application crash, or the cause of the stall. No blind local retry followed.

## CI validation and remaining gates

The candidate was published only to `validation/reader-status-ci` and draft PR #3 targeting `implementation/native-foundation`. CI14 completed the existing full synthetic test, assembly and lint workflow; independent artifact and bounded ordinary/article frame reviews passed. Final results-doc CI and guarded integration remain pending. The implementation branch and main have not changed for this candidate. There are no workflow, permission, credential or signing-key changes.

Normal/compact single-segment captures show reduced chrome and reachable tails; original explicit errors and multi-segment controls remain. Some scrolled toolbar captures retain a visual-evidence limitation. No live API/account write or physical-device validation was performed. Live HTTP 403, real TalkBack/device behavior, complete Flutter parity, durable reader restoration, arbitrary inline media and prior vault-v2/debug-signature limitations remain open or unchanged. The CI14 signer differs from CI13; no main merge, release-signing continuity or upgrade compatibility is claimed.
