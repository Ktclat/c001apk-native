# Native feed reading recovery — scoped implementation

Baseline: native main source tree `ab9c61abd2508d5943671835ce69e436f8b59238`.
Flutter reference: published `78e62d33`, tree `8ef26b5`.
This document describes a development candidate, not a live-service repair or full Flutter parity.

## Findings traced end to end

The ordinary Home leaf already navigated to `feed:<id>`, `BrowseViewModel` already
called `/v6/feed/detail?id=…`, and the decoder already retained the entire bounded
`message` in `FullPostBody`. The separate 12,000-unit list/history preview was NOT
by itself a confirmed explanation of missing ordinary detail. Existing parser-only
fixtures could not establish that the endpoint delivered a real full body.

Confirmed gaps compared with current Flutter:

* CAPTCHA/authentication detail failures stopped at the API error; the official
  numeric public-feed representation was not attempted. Failure type and recoverable
  challenge were discarded by the view model, leaving only a generic reload button.
* Detail response identity was not checked against the requested route.
* Ten Home container templates and three already-advertised destination types had
  no real native dispatch. See `HOME_TEMPLATE_PARITY.md` for their separate contracts.
* Pinned and reply-to-me rows in the detail payload were not integrated with replies.

These are proven source-path gaps, NOT proof of which one affected the user's
installation. In particular, HTTP 403/risk-control/protocol access is still unproven;
no user account, cookies, official device identifier or live endpoint was used here.

## Reading/recovery behavior

1. Read the real API detail with one captured identity. Validate numeric response
   identity (including leading-zero aliases) without integer overflow. Preserve the
   full ordinary or structured source separately from the list preview.
2. Only API CAPTCHA/authentication failures may attempt exactly one official
   `https://www.coolapk.com/feed/<numeric id>` GET. No generic 403, risk-control,
   rate-limit, malformed-response, timeout or network-error fallback is performed.
3. Public reads have no Cookie/Authorization, inherited request interceptors,
   authenticator, cookie jar, HTTP cache, redirect following or Set-Cookie adoption.
   Response allocation remains bounded at 4 MiB; normal parser limits remain.
   App-only HTML, summary-only JSON, mismatched IDs and unreadable bodies are NOT
   promoted to successful full detail. No summary extraction or fabricated feed.
4. Cookie-free public data cannot establish ANY account action state, including
   nested highlighted replies. A visible notice explains public vs account detail.
5. Existing eligible authenticated challenge continuations now reach the detail
   UI. Human verification and one explicit read retry are separate gestures.
   Proofs remain memory-only/redacted; expiry, owner/session change, cancel, route
   departure and duplicate consumption prevent replay. Guest continuation boundaries
   are not weakened. Guest failures offer a genuine login/reload path, not an
   unimplemented import action. Official device import remains a separate gap.
6. A failed/malformed account refresh or verified read retains an already readable
   **public fallback** body. An authoritative API revision that is itself unsupported
   replaces a previous API body with the explicit unavailable state and clears its
   reader bookmark; stale authoritative content is not silently retained.
   Leaving a detail cancels its in-flight read and recovery capability; scrolling
   the lazy body offscreen does not revoke route-owned verification.
7. Public fallback comments are explicitly requested; successful API detail keeps
   existing usable automatic comments for both guests and accounts. Comment CAPTCHA
   is reported honestly: this screen does not yet continue comment verification.
   Pinned/self reply ordering is preserved while newer reply-list rows win for
   text/counts/action state; duplicate IDs are not rendered twice.

## Verification and honest limits

`feed-reading/*.json` are authored production-shaped synthetic envelopes matching
Flutter's actual fields, not claimed captures. Tests traverse NativeApp → Home leaf
→ API challenge → loopback public detail → real decoder/parser → complete long tail,
photos/structured text-image-share blocks → explicit comments → Back. They also
cover failure/retry, authenticated recovery, guest API success, stale route/account,
no-public-cookie flow, signed-header allowlist, wrong IDs, size limits and no loops.
Separate provider UI tests use synthetic graphic bytes and typed test input; no
actual CAPTCHA is solved and no provider page is contacted.

Focused-v1: 71 executed, 5 failures. Focused-v2: 28 executed, 4 failures. Their frozen
source and evidence are preserved outside the repository. One v1 failure exposed
misleading no-public-adapter error wording and was corrected. The remaining v2
failures were diagnosed from pixels/request counts: a merged-semantics image node
and a raw StateFlow poll that did not drain paused Robolectric Main. Stronger actual
image/cell assertions and UI-error synchronization replace those harness mistakes;
the combined full gate is still required after the accepted focused run below.

Unsupported raw article block types/over-budget content, arbitrary event HTML/media,
full Flutter menu/social parity, official device import, physical-device/TalkBack,
real-account verification and live-service success are NOT claimed by these tests.

Focused-v3: 122 executed, 121 passed / 1 failed. At 1.6× the comments controls
were not yet composed in their separate lazy-list item; the test now scrolls the
parent to that item before requiring a visible CTA, then still asserts no automatic
comment request and exactly one explicit request. Focused-v4: **124/124 passed**
(2026-10-25), including source-tab selection/scroll → child detail → Back without
refetch. All 446 frozen input hashes remained unchanged through that run. Exact
logs, XML, screenshots and v1–v4 failure history are archived externally. Independent
review accepted the scoped source and normal/dark/large-font synthetic pixels.
This is focused acceptance only; combined settings/build/lint acceptance is pending.
