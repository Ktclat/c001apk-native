# Notification-list read verification — validated candidate

## Scope and baseline

This increment retains the independently accepted notification feature foundation `7b1a671b19ce3d8de74c2ca42829e28779ac8205` and is integrated on combined foundation `c5d57a831287fc1b884f3d4de3d417dab018073f` (tree `e6caaaab5f31d39c903336ba542de54c62ef8bee`), source-equivalent to published `ec6509507684d037148bf310c02c3fbee14ce206`. Final public-user/connections pagination corrections are retained. It extends only the five non-message notification categories: mentions, comment mentions, likes, followers and existing system notices.

The message/conversation list, chat, notification deletion, sending, publishing, uploads and login are not opted into the new read continuation. No provider origin, CSP, JavaScript bridge, SDK, credential-store or proof-field allowlist is broadened. The accepted notification decoder, duplicate-record pagination and deletion provenance are retained.

## Visible flow

1. A source-defined challenge from the exact authenticated notice GET exposes **亲自完成验证**. Generic HTTP403, HTML, plain risk errors, redirects, rate limiting and malformed responses do not invent a challenge.
2. The existing visible human-operated verification host is opened only by that button. Cancelling or leaving does not retry the list.
3. A valid user proof returns to the list with **验证完成，重新读取通知**. Completing verification alone sends no list request.
4. Only an explicit reread consumes the one-shot capability. After verification, both **验证完成，重新读取通知** and the ordinary **重试** control use the same displayed verified capability; top-level **刷新** remains a fresh read. A retained generic retry callback cannot act on a replacement failure. It reuses the original category/path/page/firstItem/lastItem and immutable account snapshot. A second challenge or failed retry does not create an automatic loop.

Failed refresh and pagination retain this owner's prior rows, cursor and scroll. Only successful refresh replaces rows; successful pagination appends, preserving distinct notification occurrences. Category changes, child/route departure, pause, cache eviction and owner replacement revoke the read attempt and close pending capabilities. Returning to retained safe rows never resurrects an old proof.

## Ownership and cache boundaries

- `SocialRepository.notices` admits the explicit five-category allowlist and calls `forSingleOperation()` before requesting the immutable factory-captured session. A public `operationScoped=true` constructor flag alone cannot satisfy the accessor.
- The shared authenticated-read helper retains the existing owner/generation/device/raw-revision checks and transport's exact-operation replay binding. Personal/count readers keep their existing direct default behavior and original shared memory instance.
- Notice verification has a separate memory-only `ReadSessionMemory`. Private challenge overlays accept only source API `SESSID`; challenge/provider/WebView `uid`, `token` or cookies cannot replace account credentials. An accepted blank-field proof can be reused only for notice reads of that identity, within the existing bounded memory lifetime. Field proofs are not cached.
- A final `NotificationReadAttempt` binds a challenge to its route attempt. The memory cache's final binding/entry/version assignments and cancellation share one monitor. Identity checks, clocks, parsing, merge preparation, private-overlay snapshots, network and provider work occur outside that monitor. Accepted entries retain the raw identity, not a route lease.
- Lock direction for these guarded commits is `ReadSessionMemory → NotificationReadAttempt`; there is no attempt-held call into `PendingRead`, a private verification overlay, `AccountStore`, transport or SDK. UI cancellation releases the attempt monitor before cancelling the job and capability.
- Version checks reject stale challenges and prevent old success, old challenge, close/finally or a cancelled capture from erasing/overwriting a newer accepted read session. Ordinary **accepted read-memory** SESSID rotation uses the same guard. Existing raw AccountStore ordinary Set-Cookie handling is unchanged and is not claimed to have this new route-atomic guarantee.

## Pre-publication retry-control correction

Final parent UI review found that the generic **重试** button still started a fresh request after successful human verification, discarding the valid proof. This candidate now binds that callback to the exact displayed failure, captures its refresh/pagination mode, and sends its VERIFIED capability through the existing guarded one-shot load path. Unverified/closed current errors retain ordinary fresh retry behavior; stale callbacks and duplicate clicks cannot act on a newer failure. Top-level Refresh and all transport/security policies are unchanged.

The primary root flow remains covered. Additional actual-root and refresh/pagination fixtures cover verified generic retry, closed-capability fresh retry, stale callbacks before and after proof, duplicate clicks, exact original GET/cursor and row retention. Primary and generic captures use separate directories. The prior 1,372-test gate/APK below remains historical evidence for `73657c8`. The corrected runtime passed the fresh gates below; the former APK is not the final corrected artifact.

## Final corrected runtime

The frozen correction `3bfeaea716624d0264a1676f3eb86f7b757e2a60` (tree `93a827c3cc6c17553f9f5562c60b067e5e13c1db`) passed **265/265 affected tests in 40 nonempty suites (41 XML files)** and **1,375/1,375 full tests in 207 nonempty suites**. Both runs had zero failures/errors/skips and all 540 frozen inputs unchanged. Assemble, all-test compilation, lint, 31 font tests and 20 provenance checks passed. Independent review accepted the narrow displayed-failure identity/VERIFIED delegation change; transport and security-policy code is unchanged from the prior reviewed runtime.

The final corrected debug APK is bound to that tested commit, not the later documentation-only completion commit. SHA-256: `08e20468fb41bcde0fecb632f27402a0ade90ffef458378981781e0cea0d2dd9`. Embedded clean provenance, source fingerprint, signature and package badging were verified. Evidence is in `native-toolchain/notice-read-evidence/focused9` and `full10`.

Both actual-root paths—primary explicit reread and generic **重试**—passed separately. All ten freshly generated primary/generic PNGs are byte-identical to the five previously reviewed image states. The original clean-header comparison remains bound to `73657c8`; it is not relabeled as a new capture.

## Prior verification history

The first compile-only checkpoint passed in 1m47s. Independent review then found cancellation/cache commit boundary races, resolved by the narrower guarded-memory mutation design and deterministic boundary fixtures. The revised candidate compiled in 1m51s and ran 200 tests in 32 nonempty suites (33 XML files): 198 passed, two failed, zero errors/skips, with all 533 frozen inputs unchanged. Actual production wiring, deterministic memory races and the actual My/host flow passed.

Both failures were isolated without changing production behavior: the unchanged message/chat fixture needed loopback challenge-cookie origin mapping (repository rerun 14/14); the category/generation lifecycle fixture polled raw state without synchronizing the Compose main loop. Diagnostic output and cached Compose test bytecode independently confirmed the observation boundary. Root-semantics synchronization and committed category/generation markers now preserve the same 10-second bound, exact request counts and closed-proof assertions (lifecycle rerun 4/4). These narrow results do not relabel the earlier failed run. The expanded focused gate then passed **262/262 in 40 nonempty suites (41 XML files)**, with all 540 frozen inputs unchanged. The first aggregate gate assembled and compiled but stopped at two lint errors in new fixed-owner fixtures reading `StateFlow.value` inside composition; those fixtures now capture the immutable generation before composing, without suppressing lint or changing production behavior. The fresh aggregate gate on `73657c8226cecca2128c28e2e39e789a4e7069f3` (tree `bbaefc4aa3d1725c5a199c648210d08df630f64e`) passed **1,372/1,372 tests across 207 suites**, with zero failures/errors/skips and all 540 frozen inputs unchanged. Assemble, all-test compilation, lint, 31 font tests and 20 provenance checks also passed. Independent security review found no remaining blocking source finding; it separately traces one-shot cleanup, cancellation/cache boundaries and lock order.

The historical immutable debug APK remains bound to that tested commit, not a later documentation-only completion commit. Its SHA-256 is `9a6861fe98c376a4202f1fb429ae18660aebbe109151739bc1292f47f0cdcc25`; signature and package badging verified (min SDK 26, target 36). Local receipts and original frames are in `native-toolchain/notice-read-evidence/full8`, with the exact-source security receipt `security-final-73657c8.json`.

Passing synthetic fixtures cover:

- The real `AppContainer.accounts → AccountRuntime → AccountSessionAdapter` transport factory, with only persistence and HTTP execution replaced by memory/socket-free fixtures; normal notices and a challenge continuation must both work.
- Exact GET category/cursors, blank/field/graphic protocols, duplicate consumption, expiry, wrong proof, bare403/HTML/risk/redirect/oversized/invalid response, guest/stale owner/revision, and no proof propagation to message/chat/write transports.
- Delayed old initial challenge and consumed retry success/challenge versus a newer same-owner accepted session.
- Deterministic cancellation at capture, revoke, publish and accepted-session rotation boundaries; identity callbacks and clocks explicitly assert they are outside the attempt monitor.
- Failed refresh versus next-page retry, single-flight double tap, pause/resume, category/account replacement, route/cache disposal, and the actual My shortcut → visible host → cancel/late callback → proof → separate reread → feed/Back flow.
- Existing personal/count, memory/proof-field/transport/host, notification navigation/deletion and account-generation regressions.

## Matched visual evidence

Historical separate test-only captures passed **1/1 BEFORE + 1/1 AFTER**, using unchanged foundation `c5d57a8` and tested candidate `73657c8`, with identical clean HTTP403 challenge JSON, account, My → MENTIONS route, page 1 and viewport. All 529/540 tracked inputs respectively stayed unchanged. Each capture dispatched one notice GET, with zero external DNS targets or persistence writes after fixture login.

The old ordinary transport returns **请求失败（HTTP 403）** before parsing the body; the admitted read-only path recognizes the valid source challenge and exposes **亲自完成验证**. The first two auxiliary attempts incorrectly expected CAPTCHA wording on the old path; their failed receipts and original pixels are retained. The final source-supported assertion correction did not change production or the rendered BEFORE image. The initial layout-only diagnosis was incomplete.

The historical matched AFTER entry is byte-identical to the corrected full-gate primary and generic entry frames; this does not re-identify the historical capture as a new run. The corrected full-gate flows additionally preserve hostile-cookie rejection/isolation coverage, cancellation/late bridge handling, separate explicit reread, successful rows and feed/Back. No hostile-header coverage was removed to obtain the matched pair. The five original full-gate images were independently inspected; the host's blank SDK area is a ShadowWebView limitation, not evidence of live provider rendering.

All provider callbacks in fixtures are synthetic. ShadowWebView does not execute the provider SDK. No real account, real CAPTCHA solving or live-service acceptance is claimed. No artifact is published by this candidate record.
