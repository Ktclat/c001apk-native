# Notification destinations: bounded native route parity

## Scope and source

Based on native `5c81d39245c1d4a11d235e29238f53aa267b9292` and Flutter
`0d9ed002980d935c1c9351393e9f16784aa78903`:

- `pages/noitfication/notification_page.dart`, `notification_controller.dart`
- `components/item_card.dart`, `cards/notification_card.dart`, `cards/feed_reply_card.dart`, `cards/like_card.dart`, `cards/app_card.dart`
- `utils/utils.dart` (`/feed/<id>` routing discards query/fragment)
- `test/network/guest_detail_test.dart` (exact `{code:404,message:'动态不存在'}` fixture)

The four reachable My notification shortcuts (mentions, replies, likes and followers)
and the existing System dropdown share this bounded read-only increment. Private
messages/chat keep their previous renderer and behavior. Those non-message lists
previously rendered basic strings, discarded
`note`/`url`/`likeUserInfo`, and offered no non-message row destination. This increment
uses the real authenticated list repository and existing production feed and public
user readers. Notification bodies/actors and internal read destinations are now
separate from response record/deletion identity.

## Mapping and boundaries

| Response kind | Primary destination | Actor profile |
|---|---|---|
| `feed` | Valid positive numeric feed `id` | `uid` |
| `feed_reply` | Explicit validated `url` to root feed | `likeUserInfo.uid` for likes; otherwise `uid` |
| `notification`, `contacts_follow` | Explicit validated `url` to user | `fromuid ?? uid` |
| Other `notification` | First actual HTML `a[href]`, if a supported feed/user link | `fromuid ?? uid` |
| `contacts` | `userInfo.uid ?? fUserInfo.uid`, never relationship record ID | Same |

An explicit empty/malformed actor is not replaced by a recipient/original author.
The tokenizer is bounded at 12,000 UTF-16 units, 512 tokens, 128 attributes/tag and
understands quoted/unquoted/boolean attributes, comments and raw-text elements.
It uses exact ASCII HTML whitespace and declines tree-repair, template and foreign
content constructs rather than claiming full DOM equivalence. Malformed/duplicate
href attributes fail closed; the first actual href remains authoritative even if
unsupported or empty. Text/entity decoding never executes HTML
or fetches resources. Raw message retained for non-message rows is bounded at 4,000
units; decoded display text is also bounded at 4,000.

Links accept only strict feed/user numeric paths and the known Coolapk web/custom
schemes/hosts; no credentials, ports, controls, encoded ambiguity, external browser
fallback or arbitrary intent is allowed. Query keys are restricted to bounded
`rid`, `shareUid`, `shareTime`, `shareFrom`, `shareKey`; duplicate/unknown keys fail
closed. `rid` is **not** a feed ID and is never sent to `feed/detail`.

The explicit action for a comment is “查看相关动态”. This matches Flutter opening the
root post. This is **not exact-comment scrolling or complete reply-thread parity**.
A missing root URL does not fall back to the comment's own ID. Missing, unsupported,
and over-limit targets have explicit different states. The existing reader's
sanitized error remains authoritative; only the exact known feed/detail404 fixture
maps to “该动态不存在或暂不可见”. Security/verification failures and other endpoints,
codes/statuses/messages keep their original behavior.

A terminal failed detail with no readable validated/cached/public post now omits
phantom comment-count/reply/sort controls. Loading states, retained readable posts,
and independent comment failures keep their existing controls and policies.

## Ownership, back navigation and existing actions

- New category pages live only in memory under the root account/generation/repository
  owner, shared across initial notification routes. At most 3 LRU categories × 500 rows
  are retained. The selected category is route-owned. Dropping all notification
  routes, account changes or eviction clears retained data and invalidates publication.
- Completed pages and nonzero LazyColumn position survive an actor/feed child→Back.
  Departed in-flight reads are cancelled; late old-owner responses cannot publish.
  Refresh and pagination failures remain visible; retry repeats the failed cursor.
- Non-message occurrences are preserved, including identical record IDs with different
  liker actors/entity kinds. Stable occurrence keys do not substitute actor/destination
  IDs into pagination or deletion. Repeated raw boundary IDs still advance the
  non-message page number; empty/explicit terminal responses and Int.MAX_VALUE stop
  it. The UI also stops at its local 500-row cap and says refresh updates the list,
  not that refresh bypasses this cap. There is no auto-fetch loop.
- Non-message retained record fields are bounded; conversation keys, pin state and
  message-image payloads are not retained for these notices. Message/chat fields are
  unchanged.
- Deletion still requires original `entityType=notification`, original record ID,
  repository-owned provenance and matching generation. No new deletion or automatic
  read-mark action is added. Private-message/chat contracts and deduplication remain
  unchanged.
- List reads retain existing authenticated immutable snapshots/device/cookie scope
  and now also verify the expected generation before dispatch. Existing feed/profile
  destination read-verification behavior is unchanged.

## Explicit remaining gaps

**Notification list read-CAPTCHA continuation is not implemented by the existing
SocialRepository.** The list displays the sanitized challenge error and permits
manual reload, but reload is not successful challenge completion. No automatic
retry, bypass, validation-cookie sharing with social mutations or claim of full
notification-flow parity is made. Destination readers still use their existing
scoped human-verification capabilities.

The existing public profile feed pager can still render a canonical “no more data”
card while offering Load More. The notification-to-profile screenshot records this
pre-existing destination-reader limitation; this increment does not alter that
generic feed pager or claim complete profile pagination parity. A separate profile
owner is addressing it; the combined integration gate must review that new frame.

## Verification

BEFORE: one production NativeApp→NotificationScreen test passed on exact tracked
baseline source, with an injected test-only synthetic HTTP interceptor. All 513
tracked-file SHA-256s matched 5c81d392. The resulting frame visibly showed unclickable
feed/reply rows and blank notification note areas. Evidence is archived separately
from candidate build outputs, including test source, XML, logs, commit and hashes.

Final frozen candidate: `compileDebugUnitTestKotlin` passed, then the original scoped
selection plus new coverage passed **104 tests / 17 suites, zero failures, errors or
skips**. The 521-file pre/post manifest includes untracked new runtime/test files;
all hashes matched. The tested feature source was a dirty worktree on the baseline,
not the baseline commit itself. The subsequent unchanged source commit binds that
manifest; a separate clean integration gate must establish final APK provenance.

Selection: `*Notification*Test`, `*SocialRepositoryTest`,
`*DetailFailureContractTest`, `*MyRootRequestRoutingTest`,
`*FeedCommentControlsPolicyTest`, `*FeedReadRecoveryNativeAppTest`.

The 15 mounted NativeApp cases cover all four My shortcuts, actor identities,
root-vs-reply IDs, System known/unknown links, repeated raw IDs across three pages,
pagination failure/retry, exact category/nonzero scroll restoration on child Back,
local cap/refresh, delayed old-account reads, list failure/auth/challenge states,
and unavailable detail versus valid-body/comment-failure and loading controls.
Repository/parser/cache policy cases cover provenance, strict targets, malformed
actors, size bounds, LRU eviction, stale-generation dispatch and terminal/overflow.
All HTTP fixtures are synthetic; unknown network destinations are rejected. No live
account reads or mutations were performed.

Nine original AFTER PNGs show the production components (not independent mockup
screens): list, related feed, follower profile, unsupported link, list error,
unavailable detail, paginated list, child Back and cap. Paginated/Back frames are
byte-identical. BEFORE and each unique AFTER were inspected individually; an
independent reviewer also checked their original pixels and source manifests.

### Preserved failures and causal test-harness diagnosis

- Initial broader attempts were explicitly interrupted after repeating AppNotIdle
  failures; neither is reported as a terminal full-suite result.
- The old delete UI test genuinely failed because its notification row moved below
  the viewport. It now asserts no Delete on the initial feed/reply viewport, scrolls
  the real list, then asserts exactly one original-notification Delete and cancels.
- An isolated original-order `DeleteUI → State(2) → Counts` run reproduced 4 tests /
  1 Counts failure. State-only → Counts and other-new-tests → Counts passed. The
  new pure State tests had published Compose snapshots without a UI test owner.
  Their assertions and cleanup now run wholly in an un-applied mutable snapshot,
  disposed in finally. No production scheduler, clock, idle check or timeout changed.
- A first narrow 4/0 rerun was reordered by Gradle with Counts first, so it was not
  accepted as exact-order causal proof. A deterministic external JUnitCore launcher
  using the unit-test runtime classpath/JVM settings then ran the original
  `DeleteUI → State(2) → Counts` order and recorded all four passed, with no skipped
  cases. It runs outside committed tests, with no idle/scheduler overrides.
- A superseded 101-test gate passed before the final repeated-ID pagination delta.
  The first attempt to compile that delta failed before tests due to an accidentally
  edited unrelated pagination expression; it was restored. The final 104-test gate
  supersedes it. The first external launcher failed setup on buildSrc; a later one
  completed all four cases but lingered on non-daemon Robolectric threads and was
  explicitly interrupted. Both receipts are preserved, not represented as green
  Gradle processes. The final launcher exits only after checking all JUnit results; its final Gradle
  process exited 0 (4 tests, zero failures/skips), with the required start order logged.

Local evidence root: `../notification-route-evidence/` (`before`, failed/interrupted
attempts, `order-diagnosis`, `final3`, ordered-launcher receipts and source binding).
These are scoped local checks, not a full unfiltered application gate. Synthetic
fixtures do not establish live endpoint, real account, physical Android, TalkBack
or performance acceptance. No push or publication was performed.
