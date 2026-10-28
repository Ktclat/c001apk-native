# New source-only QA reconstruction plan — NOT EXECUTED

The previously accepted `efc669…` APK, `c45da00…` AndroidTest source, their complete
raw evidence, original signing key and final 223,696-byte overlay are not present
in this restored environment. Historical conversation claims are not a new test
result. **Every scenario below is NOT_RUN.** Recovered older code is a starting
point only. The new freeze, build, signer, APKs, runtime manifest, original frames
and independent verdict must all be generated and bound again.

## Non-negotiable prerequisites

1. Root freezes the reconstructed production tree; its normal full gate reaches
   terminal green and releases the shared heavy-build lock. No QA build/AVD yet.
2. Independently review this newly written test overlay. Keep all fixture data,
   fake account state, interception and dependencies in androidTest only.
3. Build the test APK without replacing the separate exact production APK with
   the incidental overlay-built app. Record new signer certificates (the old key
   is missing; never assume the old signer), hashes, sizes and DEX class-definition
   overlap. Check a freshly enumerated runtime input manifest, not the old 433 list.
4. Sole owner creates/boots a disposable API26 official-image software AVD after
   builds stop. No old lock deletion, root, TLS/firewall/security changes or
   concurrent Gradle. Require actual Launcher drawer → Back pixels and modal-free
   responsiveness, then install and pull/hash both exact APKs.
5. Run only the explicit filtered cases below with unique run IDs. All synthetic
   API transports deny non-GET/unlisted scheme+host+port+path before sockets. No
   real accounts, social writes, credentials, private user text or screenshot.
   Do not probe restricted image.coolapk or repeat the historical failed avatar
   attempt. Public-CDN recovery remains unqualified.

## Fourteen app scenarios (seventeen invocations with three narrow repeats)

| ID | New test target | Required visible/state evidence |
|---|---|---|
| E1 | `actualFeedTitleBodyAndHotReplyUseBundledArt` | Real NativeApp feed title/body and hot reference; original `[受虐滑稽]`/`[笑哭]` art, Unicode 🎁, unknown literal; special photo/role/pinned labels; HTML full reader; ordinary FeedReplyRow and nested PagedPostText separately. Exact `本地图像[笑哭]。` occurrence, no U+FFFC, per-body painted occurrence count, last local art actually scrolled into view. |
| E2 | `actualSettingsOffOnChangesRenderingWithoutChangingFeedSource` | Real Settings UI with memory repository toggles images off/on; original payload unchanged. Explicitly not MainActivity persistence. |
| E3 | `directPointerOnLinkedEmoteDoesNotActivateParentFeedCard` | Exact asynchronously parsed body ready; linked-art glyph bounds inside displayed text, visible view-level pointer dispatch; only expected child feed9802 GET, no parent activation. |
| E4 | `darkLargeFontFeedAndDetailRemainReadable` | Dark effective LocalDensity 2.4; Markdown formatted/raw/code literals, normal/nested reply art and wrapping. No OS-font claim. |
| E5 | `darkLargeFontHtmlLocalImageOccurrenceRemainsVisible` | Dark effective2.4 HTML preview + full reader: exact local-img line and own last art visible after ordinary scroll; no unrelated smile substituted. Repeat narrow. |
| E6 | `fullReaderRawAndActualCopyPreserveOriginalTokensAndCode` | Fresh empty task-only clipboard; actual visible long-press → Select all → Copy; original toolbar/window/bounds capture and current descriptor+enabled+clickable check. Exact independent formatted and raw literals; no duplicate role suffix, filtering or self-oracle. The independent97/117 literals are now recovered and match historical hashes under verified-oracles; reuse those exact oracles, not old clipboard results. |
| P1 | `actualEmotePreferenceToggleSurvivesReentryAndRecreation` | Unmodified MainActivity Settings content toggle off → Back/reentry → recreate → on; read only task showEmoji and restore original finally. Select unique actual selectable bottom-nav item. |
| A1 | `actualReply500AnchorSurvivesCachedPrependWhileChild43IsOpen` | Existing StructuredArticleRetention fixture + normal BrowseViewModel.load seam. Article page3, visible exact reply500 bounds → link child43 → refresh cached parent prepend490/491 → Back. Compare displayed500 bounds BEFORE any corrective scroll, detail42 read1, reply reads2, page index2 retained. Saved bookmark is not live list-state proof; actual bounds are. Only afterward scroll to post and display page3. |
| N1 | `actualSystemDropdownOpens501Feed502AndBackRetainsUnsupportedRowPolicy` | Actual NativeApp MENTIONS dropdown → SYSTEM → notice501 → feed502 → Back. Exact bounds/scroll/category before/after without corrective scroll, listread1. Unsupported503 visible unavailable-text view-pointer (avoid delete control), no click action, no extra GET, native foreground. |
| M1 | `actualCarouselManualRetryDoesNotOpenItsParentAndDiagnosticsAreBounded` | Real production Coil loader/fetcher/decoder + synthetic byte-response seam; decode error → one explicit visible ≥48dp retry → actual loaded pixels, no parent navigation. Diagnostics group counters visibly scrolled into frame, closed safe categories, no URL/header/body leak. |
| M2 | `blockedBannerStopsAfterOneRetryAndGallery404StaysExplicit` | Synthetic567 first+one manual retry then terminal, no loop; gallery404 explicit error/retry. No live-CDN claim. |
| M3 | `darkLargeFontRetryIsOnlyShownWhenGlyphAndTouchBoundsFit` | Short container hides clipped retry; roomy220dp at dark2.4 shows readable glyphs and ≥48dp actions. Actual inherited parent navigation versus retry event separation. |
| G1 | `productionGifDecoderAnimatesOriginalWhileGridUsesStaticThumbnail` | Static thumbnail, exact original request, actual production loader IMAGE success delta with **observed** source distribution (synthetic response may be DISK); owned original image plus success-only Zoom/Fit. ≥1.5s after qualified loaded origin, ≥3 original post-settle frames and ≥2 artwork hashes with outside motion absent; independently visible artwork changes; normal Back to static grid. No decoder/clock freeze. |

E4, E5 and M3 repeat under logical `wm size 640x1200` on physical720x1280/320dpi;
record letterboxing and restore the exact original override. These are three
repeat invocations, not new unique cases.

## Added summary scope (14th app scenario)

`fourLiteralSummaryPathsKeepArtOverflowParentRoutesAndReadOnlyMessages` covers
four newly changed real NativeApp paths: Home MESSAGE literal text retains its
secondary-container ink/no HTML destination; local-follow title/body retains
8-line ellipsis and existing parent feed7 route; unsupported notification text
has local art but no external/open action; private summary/conversation text keeps
literal HTML, only the existing Open Conversation button navigates, and blank
Confirm Send stays disabled. All transports deny non-GET before sockets, no
message is sent. This extra scope is not covered by the old13-case review.

## Six separate measurement controls

1. Early static-image fade cannot qualify after settling.
2. Delayed load + its fade cannot borrow elapsed time from pre-load observations.
3. Genuine post-settle temporal change needs ≥3 samples and ≥2 hashes.
4. Wrong/null foreground, window, bounds, dimensions, or outside motion resets
   the qualified origin; an invalid sample is never success.
5. Bulk image/outside digest exactly equals a naive row-major big-endian oracle
   for seeded random pixels, nonsquare ROIs, edge/clamped ROIs and clock overlap.
6. One-pixel artwork versus outside mutation and dimension changes are correctly
   distinguished; clock exclusion is only the actually scoped portrait rectangle.

New controls may be pure Kotlin/androidTest, but count separately from app cases.
Do not recover old PASS records as inputs.

## Evidence engineering constraints

- Preserve original full compositor PNGs, not rebuilt/cropped expected images.
  Static helper from the old patch needs a newly reviewed unique run-directory
  change. Exact foreground/expected state and ≥1.5s frame settling are required.
  Null-root fallback only positive exact native ComponentActivity focus; wrong
  nonnull package fails. Screenshot rejection saves diagnostic original.
- GIF must not call synchronizing Compose semantics/idle APIs while animating.
  Observe the same current UiAutomation root and owned success-only controls.
  Bounded normal Compose-frame commits are allowed only to commit navigation
  before the qualified loaded window and later Back, never during sampling.
- Use screenshot acquisition timestamps, not PNG/hash completion times. Preserve
  screenshot brackets and separate PNG/getPixels/ROI/outside timings. Keep 12s
  sampling budget and1.5s settle; do not merely increase timeouts. Bound platform
  calls honestly: a stalled OS call can exceed the loop budget and is not PASS.
- Write GIF stage breadcrumbs and the original Throwable to plain files inside
  @Test before teardown/UI diagnostics; preserve assertion failure separately
  from subsequent runner interruption. Never use a second UiAutomation client.
- Wait the same exact body matcher once before traversing async parsed text;
  title-level art does not prove body readiness. Normal scroll to the last inline
  occurrence and original pixels establish visibility, not semantics alone.
- Copy: after external Select-all, normal Compose sync with clock/monotonic
  receipts, then unchanged bounded visible Copy lookup. Fresh clipboard readback
  must equal the independent expected literal. Failure diagnostics are best-effort
  and must never mask the primary exception or qualify a pass.
- Raw parser requires a start for every terminal PASS. Preserve FAIL, ERROR,
  ASSUMPTION, UNKNOWN and INCOMPLETE rather than equating runner OK with success.
- Final cleanup restores only task settings/override/rotation, pulls exact APK
  hashes again, ordinary sync/owned emulator stop, lock-release receipt. A lost
  process/session is unverified cleanup, not fabricated success.

## Original bytes available versus rewrite required

See `../RECOVERY-MANIFEST.json`. The old published28-case source can supply actual
root setup and static capture patterns, not the missing new13-case implementation.
The final inline, media replay, animated evidence, controls, anchor and notification
Kotlin files must be rewritten/reviewed. The old GIF's hash/size do not recover its
bytes: generate a new clearly synthetic two-frame asset with new provenance if no
actual source bytes return. No original screenshot/XML/receipt is recreated.

## Added personal-follow scope (15th app scenario; NOT_RUN)

`EmulatorPersonalFollowTest.actualPersonalFollowUserEntityAndBackStayReadOnly` uses the byte-exact pinned `reachable-cards/personal-follow.json` and synthetic `icon-grid/icon-5.png`, memory-only uid123, actual NativeApp `mylist:FOLLOW` → contacts1002 → user1002 → Back. The initial readiness limit remains15s. The API and PNG transport interceptors are terminal, GET-only, and deny all unlisted requests before sockets. The icon uses the production Coil factory/decoder with an explicit synthetic transport seam, not default-network or live-avatar evidence. Back can reuse or refetch page1; observed counts are recorded without a retention claim. This is a companion actual-Android check, not a causal repair of the original CI62 unit failure.

Current target: **15 unique /18 app invocations +6 separate measurement controls +2 separate egress preflights**. All remain NOT_RUN.
