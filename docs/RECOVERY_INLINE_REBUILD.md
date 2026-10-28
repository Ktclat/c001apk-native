# Inline-media reconstruction after source loss

This is a new partial-tree reconstruction based on `fdb177a` plus separately
recovered, hash-verified fragments. It is **not** the lost October 27 tree, APK,
or a reproduction of its build/actual-UI approval.

Four original files were recovered byte-for-byte before this phase:
`NativeRichText.kt`, `LocalInlineImageMarkup.kt`, `LocalImageUiRegressionTest.kt`,
and `InlineSelectionCopyRegressionTest.kt`. The separate recovery manifest
records their full original hashes. This core checkpoint does not modify them.

Newly reconstructed code closes the catalog, parser/reader, bounded pagination,
settings, picker and provenance dependencies. The exact Flutter `0d9ed002` map
has 187 entries: 183 original PNGs, three outlined role labels and the photo icon.
The PNGs total 1,702,238 bytes; the rebuilt sorted manifest matches the historical
`d866a810fcb82f2325b52c30a57e9abf9c0cd99e2bcea098e751bfefdc2408b9` digest.
That equality alone is not APK evidence. Verification checks actual source bytes,
qualified resource aliases, symlinks, exact APK inventory and uncrunched PNG bytes;
production fingerprinting includes every pinned PNG as an individual input.

Formatted previews, ordinary and structured detail, feed/reference titles,
hot/forwarded references, replies/nested replies, entity/personal descriptions,
and picker cells use the shared renderer. Event literal descriptions and raw
source remain plain. Exact local legacy `img` adapts before preflight; remote,
encoded, malformed and mixed-Markdown image boundaries remain conservative.
No hashtag URL or remote request is inferred. Link occurrences keep local offsets;
menus deduplicate destinations without removing inline occurrences. Inline links
and emoticons each remain capped at 128 per rendered text surface.

Whole-source first-close bracket context is carried through slicing, so nested
unknown markers cannot expose a recognized inner emoji at the 1200/4000-unit
page boundaries. Known token/surrogate boundaries stay within those fixed limits.
Monospace HTML/Markdown code and raw fallback remain literal. Existing settings
storage persists `showEmoji`; its real switch and MainActivity provider control
rendering, and preview geometry keys include the preference.

New recovery regression sources explicitly cover these contracts, original
97-unit Copy/raw expectations, immutable link ownership, current pointer targets,
large-font badges and the lazy menu after the inline-link cap. The old preview
100-link test now distinguishes inline anchor semantics from additional button
rows; it still requires exactly one pre-dialog menu button and lazy dialog rows.
The notices test now requires the added artwork notice rather than omitting it.

Checkpoint status: source inspected and lightweight asset checks only; new Java,
Gradle, full tests/lint and independent actual-APK UI/Copy/image gates are pending.
Do not inherit the missing artifacts' approval or publish this checkpoint as a
verified delivery. Simple summary surfaces outside the formatted reader are a
separate explicitly reviewed delta, not silently included in this coverage claim.

## Separate literal-summary extension

After the core checkpoint, a separately authorized new delta connects the simple
local-follow title/body, Home MESSAGE description, notification summary and
private/conversation message text to `NativeTokenText`. It does not parse HTML
or Markdown, discover URLs, add image requests or touch account/write operations.
Existing modifiers, colors, navigation and truncation policy are retained,
including local-follow's eight-line ellipsis. No HTML anchor in a literal message
gets activated. Unsupported notification destinations still have no open action.

To keep those existing caller constraints, the runtime `NativeRichText.kt` now
has a new derived revision: optional `maxLines`/`overflow` parameters pass straight
to its existing parent `Text`. Its source SHA changes from the recovered
`bdf6e0c103f6fb71d6eb993db426d0c79e324578c0331adfd24a5007cb761420` to
`f51f9a29b20146bd5e61d3db6cffeafe942ec641e1a2fc6db66e1aeb6df5333e`.
The authenticated `original-fragments` copy is unchanged. This derived file is
not claimed to retain the original file hash. Selection isolation, alternate
text, link ownership and code/raw/event contracts of existing callers are unchanged.

Five new regression sources exercise the literal adapter and actual template,
local-follow, notification and private-list composables using only synthetic
records/loopback reads. They check eight-line ellipsis, retained parent/conversation
routes, secondary ink, absent inline links/unsupported open actions and no sends.
These source tests and the four new actual-UI touchpoints require fresh gates;
they are not covered merely by the historical emulator case count.
