# Account-only read verification increment

Scope: My personal lists (including follow tabs and collection contents) and `v6/notification/checkCount`. Fresh actual NativeApp BEFORE frames on frozen `0d3fd3b` showed a supported v2 CAPTCHA with only Retry on both routes (`native-account-risk-before-v2`). The fixtures use synthetic accounts and loopback HTTP, with external DNS denied. Test-only cookie callback origin mapping preserves the captured official identity while asserting the exact loopback server address; it is not a production trust-policy change.

Flutter source: `FFFListController.customGetData` and notification/checkCount helpers use global `Request.get` verification. `NetworkRepo.getProfile` explicitly sets `skipCaptcha:true`, so this increment leaves the profile repository unchanged. Notification/chat history, feed picker/image resolver, and generic object-detail continuation remain outside this increment; this is not a global parity claim.

- Personal-list factories bind the expected UID before dispatch. Guest, mismatched UID, changed account/generation/raw-cookie revision and stale continuations cannot authorize a read. Exact request kind, target, cursor and refresh/append mode are retained.
- New typed `personalRead`/`countsRead` capabilities use the existing human-operated host and isolated read session. No new login or social-action policy, automatic solving, acknowledgement request or arbitrary challenge URL is introduced.
- Verification completion and one explicit retry remain separate actions. Accepted blank-field validate/SESSID may serve later reads within the existing owner/device/revision/expiry-bound memory-only cache; never login/social writes, the vault or a WebView cookie jar.
- Personal route/tab/account/repository replacement and counts lifecycle pause/disposal invalidate pending capability ownership. Discarded late failures close their capabilities. Counts-only retry does not refresh profile; aggregate refresh cancels the old challenge instead of replaying it.

Production source, focused tests and screenshots are separate acceptance gates. ShadowWebView tests do not run the vendor SDK or establish real-service completion; the pre-existing browser-isolation runtime limitation remains.

The read proof-field gate also now follows Flutter underscore syntax (`^_[A-Za-z0-9_]{1,63}$`), rather than rejecting every field other than `_v2_post_token`. Original-key collisions and explicit protected request/identity/auth/device/session/signature/header aliases remain rejected after case/separator normalization. This bounded compatibility correction does not widen authenticated write replay policy.

## Generic retry continuation correction — 2026-10-27

The personal-list **重试** control and dashboard **重试未读数量** control previously
started a fresh read even after successful human verification, cancelling the displayed
one-shot capability. Both now use the same VERIFIED capability as their primary verified
reread control. Each generic callback captures its displayed failure/result identity, reads
that capability's verification state at click time, and cannot act on a replacement result.
Personal refresh/pagination mode is captured with the displayed failure. Existing synchronous
loading admission and one-shot consumption reject duplicate clicks; rejected continuations
never fall back to a fresh repository request.

Current unverified/closed failures still permit an ordinary fresh retry. Personal **刷新**
and the dashboard aggregate refresh remain fresh admissions that discard pending proof.
Counts-only retry still does not refresh profile or revive the aggregate refresh indicator.
Route/tab/account/repository ownership and counts pause/disposal rules are unchanged. There
are no transport, credential, challenge-origin, proof-field, provider-host or layout changes.

The test-only commit `36e04cf` leaves production at `e1e7d6cc` and reproduced **9 failures
in 21 tests** (zero errors/skips): both actual-root generic retries dispatched a third GET
without verified proof, both stale same-owner root callbacks dispatched an extra GET, and
five focused lifecycle assertions exposed the corresponding proof loss/stale callbacks.
The 12 passing cases included the existing primary actual-root paths. Original logs, XML,
frames and all tracked-input checks are retained in
`native-toolchain/personal-retry-evidence/before1`; this failed run is not a pass.

The first corrected-runtime gate (`a7b8036`) ran **118 tests with one failure**. Its
counts stale-action fixture retained Compose's live semantics wrapper, which deliberately
forwards to the clickable node's current handler; it did not freeze the old Kotlin callback.
Cached Compose and app bytecode plus identity-only diagnostics establish that distinction.
The test now snapshots the actual historical callback with explicit pinned class/field
checks, asserts that the live wrapper addresses the current callback, and exercises both.
Its original no-extra-read assertions remain unchanged. A one-test red gate on `0761a02`
(production identical to `e1e7d6cc`) fails with exactly the unwanted third GET, proving the
corrected assertion catches the original bug. `after2` and `before3-callback` retain these
failed receipts and all input checks; an exploratory node-key change was explicitly reverted
before testing and is not part of the final runtime.

Final candidate `c2039a1` (production corrections `60c8b59` and `a7b8036`) passed
**118/118 affected tests across 19 suites**, with zero failures/errors/skips. All-test
compilation and the pinned runtime-cache preflight passed; all **540 tracked inputs**
matched their pre-run hashes. Evidence, 30 fresh account-flow PNGs and six provenance
receipts are in `native-toolchain/personal-retry-evidence/after4`. The final runtime remains
a two-file UI-control change; no semantic-node key or production test observer was added.

Fixtures exercise actual NativeApp → personal list / My counts → visible verification host,
cancel/late callback → new proof → separate generic retry. They assert the exact original
GET/query, immutable synthetic owner cookies, no extra proof-triggered read, single-flight
duplicates, stale callbacks before/after proof and after success, and counts/profile
independence. Separate blocked-continuation fixtures retain personal rows through refresh
or pagination and check the successful next cursor; owner/tab/route/pause and explicit-refresh
cases remain covered. Primary/generic-proof/generic-stale frames are isolated in distinct
capture directories.

All accounts, payloads and proof callbacks are synthetic, with HTTP limited to loopback and
external DNS denied. ShadowWebView does not execute the provider SDK. This is not live CAPTCHA,
real-account, physical-device or full parity acceptance; combined-source aggregate and
independent device/runtime UI gates remain separate requirements.

## Integrated rich-card fixture correction — 2026-10-27

The first combined full gate on `5c2441f` ran **1,419 tests across 213 suites with one
failure**: the generic personal next-page fixture queried **加载更多** before that lazy
footer was composed. This was not a cursor or capability failure. A paired diagnostic on
identical production (`8df991a`) preserved the original failing case and passed an otherwise
identical case using actual list traversal. Semantics showed a viewport at y=64–470, two
216px rows ending at y=576, zero footer nodes before scrolling and one visible footer after.
The original click, third-request count and exact next-page cursor assertions then passed.

Correction `8d23bee` changes only test traversal: the fixture discovers the footer through
`personal-list.performScrollToNode`, asserts it is displayed, then performs the original
click and all unchanged row/proof/duplicate/cursor checks. No production, viewport, timeout,
or request assertions changed. Independent review approved this harness correction.

The corrected frozen candidate passed **33/33 focused tests across four suites**, including
all 15 account-read lifecycle cases, six actual-root personal/count flows, personal list UI
and background fixtures. There were zero failures/errors/skips; all **550 tracked inputs**
matched their pre-run hashes. All-test compilation and pinned runtime-cache preflight passed.

The failed combined gate and paired red/green diagnostic remain preserved; they are not
relabeled as full passes. Diagnostic/focused evidence is under
`native-toolchain/personal-lazy-footer-evidence/{diagnostic1,focused2}`. The combined full
rerun and final independent emulator approval remain separate gates.
