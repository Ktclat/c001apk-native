# Personal-list rich presentation

## Scope and source

Compared native `e1e7d6cc688567536fb676a817dc630f660fa1b9` with Flutter
`0d9ed002980d935c1c9351393e9f16784aa78903`:

- `apps/flutter/lib/pages/ffflist/ffflist_content.dart` opens the typed personal endpoints;
  `components/common_body.dart` dispatches through `components/item_card.dart`.
- Flutter dispatches feed, reply, entity and known template rows using the same components as
  ordinary lists. Native `PersonalListScreen` previously shared only the entity renderer; every
  feed/reply/template became a label/body/button card. Decoded photos, forward/hot-reply quotes,
  link/relation cards and template children did not appear there.

The bounded change shares the existing `NativeFeedCard`, `FeedReplyRow`, `EntityCard`,
`IconLinkGridCard` and known `HomeTemplateCard` presentation with personal lists. The adapter
has no account, credential, mutation or local-write dependency. Like state/counts remain
display-only, like controls are disabled, and no new compose, favorite, copy/share, delete,
report, block or remote social write controls are introduced on these rows.

Primary navigation uses the exact `PersonalEntry.destination`: a private collection remains
`mycollection:<id>`; a reply's explicit source URL is not replaced by its record/reply ID;
an explicit unsupported target does not acquire an inferred public target. Source-owned
authors, references and known template children use existing typed/root link policies.
Malformed reference targets are disabled before rendering. Unknown/malformed templates and
incomplete child data remain explicit rather than silently appearing complete.

The decoder also recognizes canonical `entityType=card, entityTemplate=noMoreDataCard`, as
well as the legacy terminal entity. Only the existing exact `listCard` expansion promotes
children. A terminal nested inside an unknown template does not terminate or flatten its parent.
Raw cursor ownership, contact duplicates, row cap and account/generation request guards remain.

## Verification

Candidate `06914475743487e3e133c777d8419a9b1cfa6f80` (tree
`3f20d780038075909e603cf53fa9b611ce705778`) passed production/all-test compilation
and the second focused gate: **52 tests / nine suites, zero failures/errors/skips**.
All **391 frozen source hashes matched**. Fourteen actual-root native-graphics PNGs,
semantics dumps, XML results and logs are preserved outside the checkout in
`native-personal-cards-evidence/v2`. The gate includes the existing feed/reply mutation
request tests to check that the new read-only parameters do not alter their default behavior.

The first gate compiled but failed two of 36 tests: its gallery dump assumed only one
semantics root, and its reply assertion did not wait for deferred body parsing. The corrected
harness waits for rendered body readiness and supports modal roots; the unsuccessful v1
results/frames remain preserved and are not a passing gate. v2 also corrects nullable
personal reply counts: an absent count with nested rows says `查看回复`, not `0条回复`.
Absent, explicit zero and positive counts are tested separately.

The final combined source still requires the ordinary complete build/test/lint gate and
independent emulator UI review; a focused pass is not equivalent to those gates. This
paragraph is a documentation-only update after the runtime/test freeze.

New tests cover explicit destination authority, absence of mutation affordances, known and
unknown templates, partial children, malformed references and canonical termination. Actual
`NativeApp` tests start at My and exercise own feeds, likes, replies, received replies and
account-scoped collection contents; images/gallery, primary detail, forward, author, template
child and Back navigation are covered with intercepted synthetic GETs and offline artwork.

## Remaining limits

- This restores fields already retained by the native decoder, not complete Flutter
  `LikeCard`/reply parity. Liker avatar/time and reply-linked `data.feed` previews remain absent.
- Personal child Back retains the existing behavior: it makes a fresh owner-bound first-page
  read and does not retain the old tab/scroll offset or pages. No private rows are added to
  Bundle/disk saved state. Memory-only navigation retention is a separate change.
- Parser/media limits, unsupported formats and previously incomplete detail routes remain.
  No live service, CAPTCHA, real account mutation, physical-device, TalkBack or performance
  acceptance follows from synthetic tests or rendered screenshots.
