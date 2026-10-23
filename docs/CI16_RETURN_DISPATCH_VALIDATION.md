# CI16 return-dispatch fixture correction: validation pending

Baseline: `727eb616244cbe4af42a1af3d6af988d82191698`. Post-integration CI16 did not pass:645 tests ran with one failure in `ArticleImageLoaderTest.cancelledReturnDispatchRecyclesBeforeReleasingGlobalPermitAndNeverPublishes`. The exact artifact reports a5000ms timeout at the first `awaitQueued` call, test line183/helper275, before the cancellation/recycle/permit safety assertions. Its other nine loader tests passed. CI15 had passed the same baseline tree; that does not erase CI16's failed gate.

## Established design gap versus unproven runtime cause

The fixture assumed its fast IO operation would necessarily suspend and later enqueue a caller continuation. Pinned kotlinx-coroutines1.10.2 permits a completion-before-suspension path that returns inline without queuing that callback. This establishes a missing test precondition. CI16 did not record the required job/queue/decode counters, so the exact branch taken in that failed run remains unproven. No production cancellation defect, OOM or runner-speed cause is claimed from the timeout alone.

## Exact correction

Only one test file changes; production, composer and workflow/security configuration are unchanged. Two explicit decoder gates prevent the first two IO results from returning until each caller's first runnable has returned, decode entry is observed, the job is incomplete and no return callback is queued. The first publication flag and second result also remain unset at that boundary. The test then exercises real IO, bitmap allocation and the production global semaphore.

All original assertions remain: cancelling a blocked return cannot release its permit early; the undelivered bitmap is recycled before the third request proceeds; the first caller never publishes; the second successfully delivered bitmap remains owned by its caller; exactly three requests eventually complete. Existing five-second bounds are not increased. Setup is now inside bounded cleanup, including early failures; failure-only phase/job/request/decode/recycle/queue/gate diagnostics preserve the original exception rather than replacing it with a cleanup failure. No real network request or account operation is used.

## Validation results and unresolved readiness failure

Build81 completed successfully: all 10 loader tests passed with zero failures/errors/skips, including the corrected cancellation test; all 316 frozen source hashes matched. This validates the fixture's controlled execution, not the exact cause of CI16's earlier timeout.

CI17 (run 39889113807) executed 645 tests with one different failure. The loader tests passed, but `NativeAppRenderingTest.detailHidesDockAndBackRestoresRootWithoutReload` timed out at its unchanged 15-second visible-body readiness gate during capture, before Back executed. The first reply's placeholder was visibly inside the viewport while other replies and the post were ready. Model data was loaded, and the existing snapshot-notification probe did not resolve the placeholder. This is an unresolved intermittent readiness observation, not a demonstrated Back-navigation defect or a proven test-only issue. Preserve the original XML, trees, images and job log.

Two separately scoped temporary diagnostic observations did not reproduce that failure:

- Build82 passed the single target test with all 317 diagnostic snapshot hashes unchanged.
- CI18 on the separate diagnostic PR#5 head `8449eeac3f4a9fca3d2b27f44eb04155dcd3f2b2` passed all 645 tests (106 XML suites). The root rendering class passed 27/27; the target passed in 0.215 seconds. Its listed order and six preceding class methods matched CI17.

Both traces contained 16 states, 187 events and zero dropped events. Each state parsed on Default, published on Main, and committed ready content; neither trace contained a cancellation, exception, missing publication or missing ready commit. Instrumentation can alter timing. These observations establish non-reproduction only, not a root cause, repaired readiness defect or elimination of intermittent risk.

The temporary trace is absent from this PR#4 source and must not be merged or delivered. Its separate diagnostic APK is not a release artifact. The verified CI18 diagnostic ZIP has SHA-256 `085f0b64677acd38acd24b6737520567b85d5a61890579fce5d2d32d2756036a` and length 56,803,746 bytes. CI17's failed ZIP has SHA-256 `23654bc0a025de762c6ab13e13fb5ba2a39d567f6734df0004d18cf2e42e714d` and length 56,941,089 bytes.

## One clean formal gate remains pending

This results-only document update retains the exact single-test correction and unchanged production source from PR#4 head `6858ddd180c7847c14afc98946bc458fce47b4ef`. It requests one ordinary full test/build/lint gate without diagnostic instrumentation, not repeated random reruns until green. Any recurrence blocks acceptance and requires diagnosis from the new failure path. Even a clean gate would not retrospectively establish the cause or repair of CI17's pending-reply observation. Exact artifact review and an explicit limited risk decision remain required before integration.

The implementation baseline, PR#1 and main remain unchanged until guarded review permits this narrow correction. Live 403 resolution, actual-device/TalkBack behavior and complete Flutter parity remain unverified. Existing vault-v2 downgrade and debug-signature incompatibility limits are unchanged. No composer feature, permission, signing, dependency or workflow change is included.
