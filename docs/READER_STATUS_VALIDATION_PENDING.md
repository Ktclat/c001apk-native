# UNACCEPTED reader-status polish: validation pending

This separate draft candidate is based on implementation commit `7e60aee5159b187d8f11cdf23eb12864da2f6d41`, tree `40022933a2bffc7ed3b60c3db8177a1cdb12ebb0`. The structured-article baseline already passed CI11/12 and scoped artifact/visual review. That evidence does not cover this new polish.

## Exact scope

Eight reviewed code/test files change only two runtime UI components and their six test classes. A successfully rendered single-segment ordinary body or structured article no longer shows permanent diagnostic-like progress and end narration. Empty ordinary text still has an explicit empty state; pending and error states remain visible. Multiple segments retain concise “第 N/M 段” status, previous/next controls, final-segment notices and page-start behavior. Links, media status/errors, parser/resource limits, session ownership and retention implementations are unchanged.

Tests use actual page tags and visible pending state rather than obsolete diagnostic copy as readiness signals. Added single-segment root cases cover compact large-text exact tails, child Back, same-model return and metadata refresh geometry. Existing multi-segment page-start and retention assertions remain. These assertions have been source-reviewed, not yet executed successfully for this candidate.

## Build80: no runtime verdict

The first bounded local focused run selected the six changed test classes. Production Kotlin and Java compilation completed. Unit-test Kotlin compilation then reported no task/log progress for 300 seconds; the existing watchdog stopped the owned process at about 581.5 seconds elapsed. Its owning session returned exit code130. No tests completed, no fresh test XML or screenshots were produced, and all315 frozen source/resource hashes remained unchanged. The attempted thread sample timed out. This does not establish OOM, a code failure, an application crash, or the cause of the stall. No blind local retry followed.

## Required validation and limits

Publish only to separate branch `validation/reader-status-ci`, with a draft pull request targeting `implementation/native-foundation`. The existing pull_request workflow can run the complete synthetic test, debug assembly and lint gate without workflow/permission/credential changes. The implementation branch, PR#1 and main must remain unchanged until exact full CI, artifact and fresh-frame review permits bounded integration. A green compile or source review is not feature acceptance. Artifacts from this candidate remain unaccepted validation outputs until that review.

Fresh normal and compact single-segment screenshots must confirm reduced chrome without clipped content, while actual tests preserve errors, multi-segment controls and restoration. No live API/account write or physical-device validation is performed. Live403, real TalkBack/device behavior, complete Flutter parity, durable reader restoration, arbitrary inline media and prior vault-v2/debug-signature limitations remain open or unchanged. No main merge, release-signing continuity or upgrade compatibility is claimed.
