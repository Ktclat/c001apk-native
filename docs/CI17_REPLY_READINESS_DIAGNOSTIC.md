# UNACCEPTED: CI17 reply readiness diagnostic

This branch is temporary diagnostic source, not a production repair, release candidate, deliverable APK or accepted integration. It targets `validation/return-dispatch-ci` at `6858ddd180c7847c14afc98946bc458fce47b4ef`; implementation and main must remain unchanged. No workflow, permission, dependency, signing or composer changes are included.

## Preserved failure and evidence

CI16 failed one loader fixture before its cancellation assertions. Its reviewed deterministic decode-gate test correction remains inherited from PR4. Build81 passed all 10 loader tests; this does not prove CI16's original runtime cause.

CI17 run 39889113807 executed 645 tests with one different failure: `NativeAppRenderingTest.detailHidesDockAndBackRestoresRootWithoutReload`. Its original 15-second visible-body readiness assertion failed during capture, before Back was executed. The first reply's placeholder was visibly inside the viewport while the post and other replies were ready. Loaded model state and a snapshot notification probe did not resolve it. This is not established as a Back navigation defect, and the failure must not be excluded as off-screen content or repaired by relaxing assertions.

The verified CI17 ZIP SHA-256 is `23654bc0a025de762c6ab13e13fb5ba2a39d567f6734df0004d18cf2e42e714d` (56,941,089 bytes).

Build82's single-method instrumented observation passed 1/1 with all 317 frozen file hashes unchanged. Its bounded trace had 16 states, 187 events and zero dropped events: every state parsed on Default, published on Main and committed ready=true. No cancellation or missing completion was observed. This did not reproduce the CI17 failure and establishes neither a root cause nor a production fix. The temporary local instrumentation was archived and removed afterward.

## One full-suite diagnostic observation

The exact reviewed patch is `ec04c26b185e59534983fd01b147efc7068dec3814efd645a686cdf13ed3c5d7`. It adds bounded, opt-in state/effect/parser/publication/committed-render events and state-ID test tags. Events contain no source HTML, parsed body, account data or exception messages. The production parser dispatchers, keys, cancellation ownership and readiness behavior remain unchanged, but instrumentation can change timing.

Only the exact failing method activates tracing. Other methods do not activate or overwrite its output. Target-prefixed trace and failure tree/PNG/state files preserve evidence through the full suite. The earliest timeout trace is captured before diagnostic queries or the existing notification probe. Existing generic failure outputs remain; target copies add no further UI query or draw. The original failure is always rethrown. All assertions and the 15-second timeout remain unchanged.

Full-suite CI and diagnostic interpretation are pending. A successful full suite means non-reproduction in that observation, not resolution. A repeated failure must be classified from its earliest trace and state-ID-tagged tree before proposing any repair. All temporary main-source instrumentation must be removed before accepted integration or delivery. Do not distribute the diagnostic APK.

## Unchanged boundaries

No real account or provider writes, live-403 resolution, actual-device/TalkBack verification, signing continuity, complete Flutter parity, or PR1/main merge acceptance is claimed. The separate image-composer stage remains unintegrated and unexecuted.
