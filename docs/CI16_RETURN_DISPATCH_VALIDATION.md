# CI16 return-dispatch fixture correction: validation pending

Baseline: `727eb616244cbe4af42a1af3d6af988d82191698`. Post-integration CI16 did not pass:645 tests ran with one failure in `ArticleImageLoaderTest.cancelledReturnDispatchRecyclesBeforeReleasingGlobalPermitAndNeverPublishes`. The exact artifact reports a5000ms timeout at the first `awaitQueued` call, test line183/helper275, before the cancellation/recycle/permit safety assertions. Its other nine loader tests passed. CI15 had passed the same baseline tree; that does not erase CI16's failed gate.

## Established design gap versus unproven runtime cause

The fixture assumed its fast IO operation would necessarily suspend and later enqueue a caller continuation. Pinned kotlinx-coroutines1.10.2 permits a completion-before-suspension path that returns inline without queuing that callback. This establishes a missing test precondition. CI16 did not record the required job/queue/decode counters, so the exact branch taken in that failed run remains unproven. No production cancellation defect, OOM or runner-speed cause is claimed from the timeout alone.

## Exact correction

Only one test file changes; production, composer and workflow/security configuration are unchanged. Two explicit decoder gates prevent the first two IO results from returning until each caller's first runnable has returned, decode entry is observed, the job is incomplete and no return callback is queued. The first publication flag and second result also remain unset at that boundary. The test then exercises real IO, bitmap allocation and the production global semaphore.

All original assertions remain: cancelling a blocked return cannot release its permit early; the undelivered bitmap is recycled before the third request proceeds; the first caller never publishes; the second successfully delivered bitmap remains owned by its caller; exactly three requests eventually complete. Existing five-second bounds are not increased. Setup is now inside bounded cleanup, including early failures; failure-only phase/job/request/decode/recycle/queue/gate diagnostics preserve the original exception rather than replacing it with a cleanup failure. No real network request or account operation is used.

## Validation status

Independent source review permits controlled validation, not runtime acceptance. Build81's focused10-loader-test check is running at note preparation; no result is inferred. The separate draft CI-validation branch must run the exact complete test/build/lint workflow and preserve artifacts before the failed integration gate can be considered recovered. Do not describe the fixture correction as a proven production-bug fix. Preserve CI16's failed XML and job log.

The implementation baseline, PR#1 and main remain unchanged until exact checks and guarded review permit integration of this narrow correction. Live403, actual-device/TalkBack and full Flutter parity remain unverified. Existing vault-v2 downgrade and debug-signature incompatibility limits are unchanged. No composer feature or permission/signing change is included.
