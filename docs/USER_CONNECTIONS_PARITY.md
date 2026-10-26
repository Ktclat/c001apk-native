# User following/fans and public information — bounded parity increment

## Source contract

Reference Flutter: `0d9ed002980d935c1c9351393e9f16784aa78903`.

- `components/cards/user_info_card.dart` links following/fans to `FFFListType.USER_FOLLOW` / `FAN` with the displayed user's explicit UID. `ffflist_content.dart` selects **GET `/v6/user/followList`** / **GET `/v6/user/fansList`**. `FFFListController` sends `uid`, `page`, and received `firstItem` / `lastItem` record IDs.
- `FFFListController.handleUnique` deliberately skips deduplication for these relationship lists. Repeated `id` and `entityId` records must remain visible. Contact destination identity is the independently null-fallback `userInfo.uid` / `fUserInfo.uid`, not the outer relationship owner or record ID (`AppCard`).
- `user_page.dart`'s UserInfo dialog reads `gender` / `regdate` from the already-loaded user-space response. `UserInfoCard` reads `logintime`; both timestamps are **Unix seconds**. Gender `1` means male, `0` female; unknown is not guessed.

## Included

Verified profile header links now mount actual public-target following/fans routes, not empty navigation placeholders. Unknown counts use unnumbered labels instead of fabricated zeros. These read-only routes have anonymous avatar loading, loading/empty/error states, retry, explicit refresh, pagination and the existing one-shot read-verification lifecycle. They use the ordinary captured read transport, not the account-only personal-list factory or a new network client. Explicit positive numeric target UIDs (up to 32 characters) are required before dispatch.

The dedicated list preserves duplicate relationship records and occurrence keys, and bounds loaded display rows to 2,000. The original next wire cursor remains stored; a separate local-limit flag prevents additional pagination. The footer explicitly states the **local** display limit and that refresh updates the list, not that the server is exhausted or refresh continues beyond the limit. Known listCard containers are expanded without deduplication. Unsupported source rows remain explicit rather than guessed social destinations.

Only user/contact cards are mounted; there is no account follow/unfollow/chat/block/report action in this list. Navigation requires the decoded bounded numeric target UID even if a contradictory URL or outer UID is present. This stricter bounded user-destination policy deliberately does not open arbitrary source URLs from a relationship list. The shared read-only entity card and anonymous `PublicImage` renderer remain in use. Local filters must be available before initial reads or refresh; filtered display does not change raw pagination cursors.

List state lives under the existing account-generation-owned BrowseViewModel and route SaveableStateProvider. Child-user Back preserves cached rows and nonzero list offset. Route departure cancels reads and closes verification; abandoned non-cooperative responses cannot replace a newer visit. The existing owner-scoped preview geometry provider remains on the user profile and is supplied to the connection list.

A small **用户信息** toolbar action is available only after matching user-space identity has loaded. Its scrollable, dismissible dialog shows UID, nullable level, gender and validated registration date/duration. Missing, malformed, nonpositive, future, overflowing and millisecond-valued timestamps remain omitted/unknown, never a fabricated registration age or “just now”. Last-active labels use Flutter's seconds/minutes/hours/day/calendar-date boundaries and local timezone. These fields do not trigger another endpoint read.

## Unchanged / limits

Own-account `/v6/user/profile`, authenticated personal-list ownership, account following filters, local-follow identity bounds, account/session handling, HTTP policy and local storage schemas are unchanged. Existing profile/feed independent error recovery and asynchronous preview geometry retention remain regression targets. This is memory-only continuity, not process restoration or full Flutter parity. Name/UID copying, profile sharing, search, avatar/cover gallery gestures and social mutations remain outside this increment.

All fixtures and artwork are synthetic and network requests terminate locally. No live follow/unfollow, chat, block or report operations were attempted. Synthetic rendering is not live-service, physical-device, TalkBack or performance acceptance.

## Verification

Exact filtered gates, counts, failed attempts and original production-component frame hashes follow. Full integration assembly/lint/all-tests/CI belong to the combined parent candidate, not this focused result.

### Focused local evidence — 2026-10-26

- **BEFORE:** unchanged main `5c81d39245c1d4a11d235e29238f53aa267b9292`, existing actual-root profile case **1/1 passed**. Static following/fans and no information action are visible. Paired PNG SHA-256: `d9fbf94538b8bd9c4c2ccbd4691cf06a52eec9aaf7546cf08c0be7ec36a80cd8`. The first offline attempt stopped before tests because Android test-runtime AARs were not yet cached; the online retry warmed the official dependencies and passed.
- **Unsuccessful candidate attempts remain preserved:** two compilation attempts stopped on a missing import and new-test syntax/type issues before tests. The first focused test batch completed **143 tests, 141 passed / two failed**. One new fixture sent Back to the activity instead of the foreground dialog window, unexpectedly navigating Home. The other observed model state without driving the main-loop/semantics synchronization after a large asynchronous page decode. Corrected window dispatch and synchronized assertions retain the same production behavior; their earlier failures are not retroactively passes.
- **A real density defect was found and fixed:** original pixel review found that the separate dialog window lost the app font factor. Frozen `0bee0e5c3bd1a8d58f2ecf13c542b4ad42bbdf76` then failed the actual `TextLayoutResult` regression **1/1**, expected system 2 × app 1.2 = **2.4**, actual **2.0**. The correction restores the already-effective host density inside title, body and Close slots, exactly once. Modal dismissal is owned by the existing UID/item and account-generation composition; an account-switch test verifies fresh profile data does not reopen the old dialog.
- **Corrected gate:** frozen `f32c72a62c50e4dc3a68aa462d7a5d430df7cd84`, tree `4e63ec7f59c6489295dec122ba01fc1cbf88e309`, compiled and passed **145/145 tests across 23 nonempty XML suites**, zero failures/errors/skips. This includes **16 actual NativeApp connection/info cases**, the exact endpoint/record-ID fixtures, duplicate preservation, empty/first/next-page failure recovery, guest and invalid-UID behavior, filtering readiness/failure, five pages reaching the explicit 2,000-row cap, account changes, read-verification cancellation, modal density and dismissal, child Back, and existing own-profile/personal-list/local-follow/user-feed geometry regressions. Overlapping attempts are not added to this total.
- **Source and frames:** all **468 frozen gate inputs** matched their pre-gate SHA-256 manifest afterward. There are 29 original production-component PNGs (Robolectric API35 native graphics, pinned app fonts; normal `w393dp-h852dp-420dpi`, short dark `w393dp-h640dp-420dpi`). Dialog PNGs draw the actual Android dialog's measured decorView, not the underlying activity. Before/after child-list frames are byte-identical and the test asserts retained nonzero offset without corrective scrolling or refetch.

Selected corrected PNG hashes:

| Frame | SHA-256 |
| --- | --- |
| Same profile fixture, AFTER | `87d1a62ce719d441f1aff66b3b21e168d949322e43f549812f92cef5a6d3fae6` |
| Following list, duplicate records | `4ee79d026f04133182a1b2f770aad0987f4fd77b8919c371c1f9720dfd40c6de` |
| Child list before/after Back | `f29467c689fb49f4018f330b425fc40ab839de4e244d3a1cfcef662a561a2663` |
| System 2 × app 1.2 dialog | `6414500138e8027693055789ac795802e9e3a60fa40e66b7fc984140a7a6dd8f` |
| Explicit local display limit | `ef9b52148d242c8083d79c496bdbfc0508d3abd1cbb4c1fd983d7c72ad460a66` |

Logs, XML, failed attempts, source manifests and original frame hashes/metadata are preserved externally under `native-toolchain/connections-evidence/{before,compile-1,compile-2,compile-3,compile-4,focused-1,density-before,focused-2}`. These scoped gates **did not run final assembly, lint, the full suite, CI, live-service or physical-device/TalkBack acceptance**; those remain separate combined-candidate gates.

Independent source, XML, frozen-input and original-pixel review accepted the corrected bounded increment. The external receipt is `native-toolchain/audit-next/connections-final-review.json`; this acceptance does not replace the pending combined full gate or live/device limits above.

## Final bounded pagination corrections

The canonical Flutter terminal card (`entityType: card`, `entityTemplate: noMoreDataCard`) and legacy `entityType: noMoreDataCard` are both recognized. Frozen `88be0586d59c19b70aeb2837973cfcde98443005` passed **146/146 across 23 nonempty suites**, with original frames and unchanged-input verification archived at `connections-evidence/focused-3`; this supersedes the earlier 145 gate for that correction only.

A further Flutter `CommonController` audit confirms that relationship-list page numbers advance even when received raw boundary IDs repeat. The final correction allows this **only for userConnections**, retaining original record cursors, all duplicate occurrences, empty/incomplete/explicit-end termination, the 2,000-row local cap and an `Int.MAX_VALUE` overflow stop. Generic readers retain their existing repeated-boundary policy. Fixtures now exercise three pages with identical record IDs and different nested contact UIDs; the former test that expected a repeated ID to stop pagination was not evidence of Flutter parity.

The same exact terminal-card predicate is also applied to **only the public `user:<uid>` feed success publication**, including explicit verified retries. Raw items, source-card display, feed deduplication and preview geometry remain unchanged. Unknown templates and arbitrary nested/forwarded children are not terminal evidence, and other home/source/own-account readers are not reinterpreted. Actual mounted tests cover canonical terminal card with no More action, legacy termination, unknown card with a real next-page request, three-page contact destinations and nonzero child-Back retention. Final targeted and combined gate results are recorded separately below when terminal; earlier successful receipts are not claimed for this new source.

Final targeted receipt: frozen `8dc73579cd1fed1852df1e2007b877bc9d4814f6`, tree `83536bb0cecdb3cbad88119a541b6e6c0bc11e9a`, passed **153/153 tests across 23 nonempty suites**, zero failures/errors/skips, after successful fresh compilation. All **520 tracked inputs** matched before and after, with a clean tree; 33 original PNGs are archived at `connections-evidence/focused-5`. This includes 17 mounted connection/info tests and the three additional mounted user-feed termination/unknown-card cases. Three-page repeated-record child-Back frames are byte-identical at a nonzero offset. The preceding `e01bc60` attempt is retained at `focused-4`: 153 tests, one new fixture failure. Its XML reports a 15-second **virtual** timeout in 0.014 real seconds while the production capability retried on real IO. A test-only correction uses a real coroutine clock with the same 15-second bound and unchanged production retry semantics; no timeout or assertion was weakened. Assembly/lint/unfiltered tests remain the separate combined gate, not this scoped receipt.
