# Home list container restoration

This is a bounded Home-card increment, not full application parity or evidence that
the reported live HTTP 403 has been fixed. Source reference: the published Flutter
tree `8ef26b5` / commit `78e62d33`, `components/item_card.dart` and its card renderers.

## Proven baseline gap

Native `ContentCard` dispatched only `iconLinkGridCard`. The other ten named Flutter
templates reached an unsupported-container notice, and their children were not
rendered. This is a confirmed source-path omission; it does not establish the cause
of a particular user's server-side detail access failure.

## Added behavior

- Typed dispatch for carousel, mini scroll/grid, avatar scroll, square image scroll,
  image/text scroll, title, message, end and refresh cards. The existing icon-link
  grid remains unchanged.
- Preserve entity `title`, `pic`, `logo` and `description` separately, including a
  card child whose entity type is `feed` and whose `message_title` differs. Mini-scroll uses logo then
  picture; mini-grid uses logo; avatar-scroll uses avatar and username; image cards
  use picture. Message cards use description, not an unrelated feed summary.
- Preserve top-level containers and the original child order. A leaf's explicit URL
  is the sole navigation target, never its container ID or an inferred feed ID.
  Unknown templates and malformed/nested leaf structures remain visibly unsupported.
- Match the source carousel's exclusion of HTTP-prefixed promotion destinations;
  other malformed/unknown destinations remain disabled. Empty/single-item carousels
  are safe. Manual next/previous controls replace source wraparound behavior.
- Keep the odd final mini-grid item instead of reproducing Flutter's integer-pair
  omission. Large text wraps the grid to one column. Labels are bounded and links
  keep 48 dp minimum touch height. Square-card titles sit below images rather than
  becoming low-contrast image overlays.
- Refresh cards explicitly invoke the existing list-refresh callback. No new
  endpoint, background refresh, write operation, cookie or device identity is added.

## Verification contract

`home-cards/templates.json` is a source-shaped, locally authored synthetic fixture,
not captured user data. Contract tests cover all ten templates, distinct image
fields, explicit routes, odd/empty/bounded containers and unsupported structures.
Compose tests cover populated synthetic images, normal/dark/2× text, carousel
navigation and replacement. Actual-root tests exercise NativeApp → HTTP Home
response → image/text card → exact detail endpoint ID → full body → Back, checking
the preserved horizontal geometry and no duplicate Home/detail request.

Tests and screenshots must be executed in the coordinator's gate before acceptance.
No live Coolapk request, account mutation, physical-device or accessibility-service
test is part of these fixtures. Home media still uses the existing image loader;
unknown templates, arbitrary recursively nested cards and complete Flutter visuals
remain outside this increment.

## Previously dead linked destinations

The existing link parser emitted `event:`, `dyh:` and `coolpic:` routes, but
BrowseViewModel had no corresponding dispatcher. Known Home cards could therefore
navigate to an unimplemented placeholder even after their visual renderer existed.

- Events now read the exact `/v6/event/detail?id=…` object and its own title, picture,
  description, rules, HTML content, registration cutoff and action URL. Feed
  `message` is never substituted. Plain description/rules use a literal paged
  reader, while supported HTML content uses the bounded detail renderer. Missing,
  malformed, over-budget and unsupported content stays explicit with normal retry.
  Numeric IDs are compared canonically without integer overflow; no event becomes
  a feed. The registration URL opens only after an explicit button press and is
  never automatically fetched or submitted.
- Subscription articles use the existing `/v6/dyhArticle/list` with `all` / `square`.
  Cool pictures use `/v6/picture/list` with `recommend` / `hot` / `newest`, preserving
  complete tags including colons and query characters. Both have real tabs,
  generation-owned caches, explicit retries, pagination and local filtering.
- A failed later-page request retries its retained cursor rather than quietly
  refreshing page one. Departed tab/event reads cannot publish late results. A
  canceled retry is marked uncompleted, so returning does not misreport empty
  success. The common repository request adapter now uses the one captured session
  instead of recapturing a possibly different account inside transport.

The additional unit/model and actual NativeApp route fixtures are still synthetic.
Event arbitrary HTML/media remains bounded by the current reader subset and has no
new browser-original recovery action; no complete EventPage visual/media parity,
live endpoint success or registration submission is claimed.
