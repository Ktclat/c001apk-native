# Native Home icon links and same-layout preview restoration

## Two separable implementation increments

1. Home `iconLinkGridCard`: explicit template dispatch, ordered bounded leaf links, actual icons/titles, existing safe routing, groups of five including partial final page, adaptive columns and minimum 48dp targets, swipe and accessible dots, 80-code-point bounded labels with two-line visual ellipsis and bounded truncation disclosure. Unknown/malformed/nested/over-bound content is explicit, not recursively flattened. No new permissions, destinations, network service, or downloaded artwork.
2. List preview geometry restoration: owner-scoped memory-only 64-entry LRU, storing full bounded-body digest + typed layout key + measured whole-block height. Pending parsing reuses exact matching geometry instead of collapsing list offset. No cached HTML/ParsedPost, global cache, SavedState body, delayed corrective scroll, reader-bookmark reuse, or forced composition retention.

The layout key includes constraints, density/font scale/direction, effective text styles and touch sizing, locale/font-weight adjustment, expanded/page and incomplete disclosure state. Raw input over 12,000 UTF-16 units bypasses caching; invalid/unbounded widths, nonfinite scale, heights over 8,192dp or 131,072px are rejected. LRU eviction and changed body/layout/owner use normal loading fallback. Guarantee is same body and same layout while matching geometry remains in the current owner cache, not arbitrary cross-configuration pixel restoration.

## Evidence history, including failures

- V3: initial runner setup failures retained separately. Tests then identified async leading-body cases where normal/landscape did not satisfy positive-scroll setup, while compact/font1.6 lost visible grid after Back.
- V5: 27/27 icon-grid and synchronous-root tests passed. This did not resolve or substitute for async-body restoration.
- V7: physical coordinate hit-tests for exposed dots and final child passed after strict restored-position assertions. Independent settled-body reproduction confirmed offsets clamped to zero after Back even after parser readiness. This justified the separate geometry fix.
- V9: production compiled; 191 tests ran, 174 passed and 17 failed. Ten new diagnostics used external latch/store conditions without first synchronizing UI, so they timed out before their intended pending-state assertions. Null-store long-press hit Robolectric Magnifier PixelCopy/Surface failure; later idle failures were not treated as proven production regressions.
- V10: UI-synchronized gates established four actual-root pending/ready exact height/offset/page/no-refetch passes; a short-circuit initial-ready condition still timed out in one lifecycle case.
- V11 gate: 10/10 passed, including all four actual-root modes and six geometry lifecycle cases. Isolated ReaderBinding 10/10 passed; null-store Surface failure remained.
- V12 isolated: 12/12 passed after class-local test-only hardware Magnifier show/dismiss shadow. Real SelectionContainer physical long-press must expose copy action and copy a nonempty body substring; original-layout size comparisons and link dialog/routing assertions remain. This does not verify actual-device Magnifier or native toolbar rendering.
- Final coherent V12 matrix: 191/191 passed, zero failures/errors/skips, exit0; all 361 tracked source hashes unchanged. Thirty suites were emitted; timestamp ordering is archived in suite-order-summary.json.

## Visual evidence boundaries

Actual NativeApp Home, child/Back, pending and settled screenshots are valid rendered-window evidence at normal, compact, landscape and font1.6 settings. Grid component images include 1/4/5/6/10 counts with both pages and original locally drawn fixture art; all image requests are intercepted offline.

The four V12 null-store PNGs are INVALID AS VISUAL EVIDENCE: Activity-only capture omitted separate Dialog windows and transparent background made body text unsuitable for presentation. Retain originals for traceability; do not present them as dialog/body pixel validation. Their associated functional assertions remain valid.

## Remaining gates and limits

Independent final review and full regression/CI are required before merge. No real-device parity, real account/server behavior, signing, or live 403 issue is claimed resolved. Flutter/native whole-app parity is not claimed; this is the bounded Home template increment and independently justified same-layout list fix. This validation note makes no publication or merge claim. The 191 tests are a focused matrix, not the full repository suite. Full CI remains pending for the final published tree.


## Reproduction and source binding

Implementation base: `8faacd13b2b2da442beebbb25464b8b18fa0430b`, tree `a2afd5fd39cb3233add275e81baae037cec7bfac`, branch `implementation/native-foundation`. Main is not the integration target.

The final focused run used the pinned toolchain with Gradle offline, no daemon, no parallel work and one worker, running `:app:testDebugUnitTest` with these unchanged class filters:

- `*.IconLinkGrid*`, `*.PreviewGeometry*`, `*.PreviewNullStoreLayoutTest`
- `*.PagedPostText*`, `*.PostTextPublicationTest`, `*.FullPost*`, `*.Reader*`
- `*.NativeFeedCardTest`, `*.InteractiveFeed*`, `*.NativeReplyRouteRequestTest`, `*.FeedReplyRowTest`, `*.PersonalList*`

The same 361 tracked source files were SHA-256 verified before and after execution; only this validation note was added afterward. No runtime source or tests changed after the successful coherent run. Fixtures are synthetic and use locally authored originals; no live account or service request forms part of this evidence.
