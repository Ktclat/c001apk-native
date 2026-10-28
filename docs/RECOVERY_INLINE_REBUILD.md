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

## CI61 oracle / semantics-target corrections (new, NOT_RUN on JVM/Android)

[CI61 run 40639669858](https://github.com/Ktclat/c001apk-native/actions/runs/40639669858)
ran remote source `5142324ee1d8ade29155c4eb27617ab9139ad181` and failed with
12 failures among 1,510 JVM tests. This follow-up is based on integrated `7a955da`;
it addresses four of those failures without changing production Kotlin or artwork.
The original run is still failed, not a passing gate for this patch.

- Two `InlineEmoticonAssetsTest` failures came from six incorrectly extracted
  test-oracle tokens. The exact pinned Flutter file uses ordinary Dart strings:
  backslash-dollar in source decodes to dollar, not backslash-dollar at runtime.
  Native catalog keys already have the correct runtime form. The second failure
  was `images[token] == null`, **before** PNG loading/decoding, not a corrupt PNG.
  See [Dart string literals](https://dart.dev/language/built-in-types#strings) and
  the [Dart SDK dollar-escape diagnostic](https://dart.googlesource.com/sdk/+/refs/heads/base/pkg/front_end/messages.yaml).
- `actualLocalFollowSummaryKeepsEightLineEllipsisAndOriginalParentRoute` selected
  merged `local-follow-card:7`, whose text list includes both title and body.
- For `actualFeedTitleBodyAndHotReferenceUseArtAndLinkTapDoesNotOpenParent`, source
  and selector inspection support a merged-parent/first-title layout inference.
  Its original XML proves only empty link annotations at line 58; stdout/stderr
  are empty and no screenshot of this exact fixture was present in the CI bundle.
  Unlike the LocalFollow XML, this is not a recorded merged-tree observation.
  The title correctly has no anchor, so inspecting its layout cannot test the body.
  Both tests now target exact unmerged Text leaves
  and also assert `layoutInput.text`, leaving real pointer dispatch intact.
  Eight lines, ellipsis, original parent feed route, exact linked emoji/URL,
  nonzero placeholder geometry and parent-tap isolation are still asserted.

The independently reproducible oracle generator reads only the pinned Flutter
file and original Flutter PNGs (not the native catalog). It rejects unknown Dart
syntax/escapes and pins both source SHA and the complete original artwork digest:

```sh
python3 app/tools/rebuild_emoticon_fixture.py /path/to/flutter-0d9ed002-checkout
```

A lightweight source audit confirmed exactly six token-value changes; all 187
source names and all 183 PNG hashes are unchanged. The six normal `$` tokens are
explicitly required and their backslash-containing variants rejected by the
existing catalog test. The prior fixture remains in the CI61 parent revision:
`13852ba6df5f681cceb30df17b65be7e7d7d82e21485bc6b758d251dca7ea5cc`.
The regenerated fixture SHA is
`58d4abe406cb194926b60c7484d5d12587a75bfe1e3d5edbec7dd498383d6a62`.
No PNG/resource/catalog mapping, count, byte or pixel assertion was removed.

Original downloaded CI61 evidence SHA256 (kept unchanged outside the source tree):

| Evidence | SHA256 |
| --- | --- |
| debug log | `9486e2547926e36dae0f43a5836c2c98b071693cd3ea9b2f479e0ddd616e70cf` |
| InlineEmoticonAssetsTest XML | `e27d67b88b43c75e7d6cafde7819b37a0298d2f70aaeb8a3400be996e2eb94b7` |
| LiteralSummaryEmoticonTest XML | `086821a8f50fce70b3a5e236f8d560e5600018cdbfb5e5cab83508857edf41a7` |
| ReconstructedRichTextUiTest XML | `bb88ddb78ed6d2f07643e6e9b83bb9e2c0de30176b1466514949b11eb5a7a966` |

Only the offline regeneration/hash audit was executed for this patch. All changed
JVM tests, Android/emulator flows, actual visible Copy and final-APK asset/pixel
checks are **NOT_RUN**, pending independent CI and fresh exact-APK review.
