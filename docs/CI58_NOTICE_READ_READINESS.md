# CI58 notification-read fixture scheduling correction

This is a **test-only correction**, not a new notification runtime implementation or new actual-device approval. The production and build inputs are unchanged from the independently reviewed `6a5acbf79d9dc4d94ab609034ea35e0478f58609` runtime and its source-equivalent publication foundation `fa87e7b9acdfaf94eeba35031d633083dccb8f8e` / remote `7683e1258e031a2ab26e52a6ac547363c15b9463`.

## Preserved failure and diagnosis

- CI58, run `40525172241`: **1,454 tests / 215 suites, one failure**, in `NoticeReadVerificationLifecycleTest.genericVerifiedRefreshRetainsRowsAndReplacesExactlyOnce`. The earlier exact local full gate remains **1,454 / 215, zero failures**; it is not relabeled as CI success.
- CI's timeout was the first `notice-human-verify` wait, before proof acceptance or generic retry. Its diagnostic recorded exactly one initial `atMeList` GET, page epoch 1, loaded rows, `loading=false`, and no failure/capability. The timeout semantics still showed disabled Refresh, alongside row 10 and enabled Load more. This proves the intended refresh had not dispatched; it does not capture the control's state at the earlier click instant.
- A controlled local diagnostic that only held the initial response and asserted enabled-control readiness was **not sufficient**: 10 tests, one failure. The same first refresh case exposed `performMeasureAndLayout called during measure layout`, with the test frame/recomposer stack reached through `ApplyingContinuationInterceptor` / snapshot notifications from a real IO worker. This failed freeze and XML are retained, not counted as successful validation.
- Inspection of the installed Compose 1.7.6 bytecode confirms `createComposeRule()` uses an `UnconfinedTestDispatcher` by default; an explicit `TestDispatcher` supplies the test scope and frame scheduler. This explains the observed local test-confinement hazard. It does not prove CI58 had the exact same unrecorded thread interleaving.

## Bounded repair

This correction changes only the lifecycle test, using the supported public `createComposeRule(effectContext = StandardTestDispatcher())` API. Production repository `Dispatchers.IO`, transport, UI scopes, verification host/policy, and capabilities are unchanged. There is no global dispatcher replacement, arbitrary sleep, timeout increase, retry loop, exception suppression, or weakened proof/cursor assertion.

The four original refresh/pagination and primary/generic modes retain their immediate initial-response schedule. Before their first action they now require the real enabled/displayed control, exact row 10, cursor, no failure, and one initial request. An additional held-response mode proves the disabled/loading frame transitions to an enabled Refresh before the same verified single-flight flow. A standalone rule-scheduling witness invokes the actual notice repository, checks effect-before and effect-after against the Compose owner Thread, a distinct real asynchronous OkHttp response worker, the exact decoded row/request, and committed visible output; it does not add observers or hooks to the production-page modes.

All existing exact-page/cursor, retained-row, proof, duplicate-click, cancellation, route, category, and generation assertions remain.

## Evidence and approval boundary

- Frozen test commit: `17fef120324a42395e257769e577ba9a3decb85b`.
- Preserved controlled diagnostic after dispatcher confinement: 11/11 passed (seven lifecycle cases plus four adjacent state cases), before adding the standalone positive thread witness.
- A further focused run at `1e15bec…` preserved a failed proposed generic thread witness: **60 tests, one failure**. A bare `withContext(Dispatchers.IO)` inside the Compose effect was itself intercepted onto the test owner, so it could not substantiate universal IO-dispatch semantics. The replacement witness observes the actual repository's `Call.enqueue` response worker instead, retaining off-owner producer/on-owner return assertions. No failed assertion was suppressed; the scope is explicitly transport-worker → effect-owner, not every `Dispatchers.IO` boundary.
- Final focused gate on `17fef120…`: **60 tests / 12 nonempty suites, zero failures/errors/skips**; all 563 tracked hashes unchanged. The actual asynchronous witness recorded OkHttp worker thread 24 and effect-before/effect-after/Compose owner thread 18. This is scoped test-rule scheduling evidence, not a claim about the exact historical CI interleaving.
- The full frozen gate on `17fef120…` is **FAILED: 1,456 tests / 215 suites, one failure, zero errors/skips**. All eight notification lifecycle cases passed, including the transport-worker → UI-owner witness. The failure is the separate `FeedTextActionsRootTest.nativeAppMenuClicksReachRealAndroidClipboardAndChooserWithoutTransferOverride`, where `send.clipData!!` was null at line 190. This full failure is preserved and is not waived by the notification-focused pass. Clean assembly/all-test compilation/lint passed (29 warnings, one hint, zero errors); 31 font tests and 20 provenance checks passed; all 563 frozen tracked hashes remained unchanged. No blind full rerun or production change followed this result. Broader publication remains pending the separate full-gate failure.
- The 242-entry runtime/build manifest is unchanged. The main-source fingerprint remains `sha256:51d567605f8c94736edee326b0992b5f9b4f4aa43e915acd75cd0d25b9475e7a`.
- The actual-UI-approved APK remains the original `6a5acbf…` artifact, SHA-256 `ea58d71b5614949f326ec84619088442f755f4884609ce62c599ee918b366134`. A new local/CI build contains new generated commit metadata and is not claimed to be the identical APK exercised in that earlier actual UI review.
- These notification verification tests use synthetic responses; no live CAPTCHA, real credentials, service acceptance, or account mutation is asserted.

Evidence archive: `native-ci58-readiness-evidence/` (outside the repository), including CI/prior-local baselines, rejected diagnostic1, diagnostic2, final focused/full XML and source manifests. Remote publication and subsequent CI are separate steps not established by this local note.
