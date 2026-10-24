# Bounded plain-reference rendering

Base: `e2359077aae8c07c998931629584100eed570f20`.

## Why this change is bounded

CI42 failed one of 902 tests because a visible plain hot-reply preview remained at
“正在排版…” for 15 seconds. Main post text and ordinary replies were already visible.
The existing failure-only notification probe did not resolve it. A separate
observational run of the same five-class suffix passed all 68 child tests and did
not reproduce the failure. The underlying scheduling cause is still unknown.
That diagnostic overlay is not part of this change.

Already-plain reference text need not wait for an HTML parser or coroutine. The
new classification returns either immediate text or the existing HTML path. It
uses the exact original UTF-16 prefix limits (1200 for hot replies, 4000 for
forwards), and only admits a conservative graphic-character allowlist: selected
ASCII, basic CJK/punctuation, full-width graphics, and well-formed emoji code
points. Tags/entity introducers, whitespace, controls, unfamiliar characters and
malformed or cut surrogate pairs fall back. There is no general HTML detection
claim and no silent truncation-policy change. This only preserves the existing
HTML-to-plain contract; future Markdown support must select its renderer first.

The immediate branch has no reference parsing coroutine and no intermediate null
state. For admitted text, Android Html.fromHtml(...).toString().trim() and the
immediate result are verified equal. Actual HTML conversion, dispatcher, producer
keys and formatting remain unchanged. No requests, credentials, dependency,
application identity, screenshot assertion or timeout setting changes.

## Validation

- New parity tests run on Android API26 and35: the exact CI42 text, representative
  strings, every allowlisted code point in bounded batches, deterministic mixed
  strings, fallback categories and exact prefix/cut-surrogate boundaries.
- Actual card tests cover immediate text, reference/body replacement, settled
  plain/HTML transitions, formatted output, limits and deletion/restoration.
- Focused gate: **45 tests passed**, zero failures/errors/skips; all405 source
  hashes unchanged. The unchanged rendering class includes the strict15-second
  CI42 large-font case; its fresh frame contains the hot-reply text.
- Full local gate: **917 tests in142 suites**, zero failures/errors/skips,
  `assembleDebug` and `lintDebug` passed. Lint:37 warnings, one hint, zero errors;
  none is located in the changed classifier/card files. All405 frozen-source
  hashes matched before/after; manifest SHA256
  `ed02c855c7ff5fb171a23ee95d5a9051bfe4940ee004f55901a435d95f06ea29`.
- Full suite generated483 PNGs. This is not a claim that all frames were visually
  accepted. Relevant actual-card and large-font rendering evidence is reviewed
  separately; synthetic screenshots are not physical-device validation.
- Local APK:17,831,700 bytes, SHA256
  `90e3e980dcfd6c532d521d52f997bfdfcbfd731db056967dc02e87585c1b1c2a`.
  Build provenance remains honest dirty basee235, with source fingerprint
  `sha256:02cfd43f8f22e94c74619e9b246415fcb827b9cb39da94abfb5822b1fe27dea1`.
  Clean-commit CI and exact artifact review remain required before integration.

This document was added after the gate; the tested product/test bytes are unchanged.

## Explicit remaining limits

This removes unnecessary asynchronous work for admitted plain text, not a global
Compose scheduling fix or a measured physical-device FPS improvement. HTML still
uses the prior asynchronous path. Its scheduling uncertainty and the pre-existing
HTML-to-HTML transient old-value interval remain unresolved. Transition tests
check settled outcomes, not deterministic in-flight races. Native Home403, real
account/device acceptance and whole Flutter/upstream parity are not established.
