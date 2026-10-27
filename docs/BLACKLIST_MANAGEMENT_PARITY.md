# Blacklist management: scoped native parity

## Scope

Reference: Flutter `apps/flutter/lib/pages/blacklist/black_list_page.dart` at
`0d9ed002980d935c1c9351393e9f16784aa78903`. Native starting point:
`e1e7d6cc688567536fb676a817dc630f660fa1b9`.

- User/topic rows now open the existing native user/topic routes. Native route
  identifiers remain raw; the repository encodes HTTP query values. Special topic
  characters such as `a/b #?% 中` are not interpreted as a path or double-encoded.
- User and topic drafts and scroll positions are independent and saved. The root
  retains blacklist state while a detail child is open and removes departed routes.
- Clear is explicitly confirmed and only affects the selected category. Opening a
  dialog assigns a saved opaque request token. Old confirm/cancel callbacks cannot
  operate on a replacement dialog, including another dialog for the same category.
- Category exports are escaped JSON arrays compatible with Flutter's list importer.
  **完整备份** retains the native combined-object format (both categories). Existing
  combined-object and legacy category-array imports remain merged, not replacing.
- Export scope is fixed when the system document picker is launched. Export reads
  the latest persisted data for that scope after selection; switching categories
  cannot include the other category in a category export. No blacklist content is
  put into Android saved-state Bundles.
- A saved pending request is consumed once. Cancel, absent request ownership, an
  orphan result after screen removal, and duplicate results do not import/export.
  Same-frame repeated picker clicks are checked against live pending state.
- Local mutations are single-flight. Add snapshots the accepted kind and draft;
  a newer draft entered while saving is pending is not erased. Destructive row
  controls reject another visible category and absent rows.
- Export encoding checks the same 1 MiB UTF-8 limit as import **before opening the
  output stream**. Full-backup schema, strict parser limits and atomic merge remain.

This is local-list management, not account credential export, general app backup,
full Flutter parity, a new topic timeline, or a live-service acceptance result.
Opening a detail may use the existing read-only repository; this patch adds no
social mutation endpoint. All destructive/export tests use authored synthetic data.

## Cancellation and files

Local operations are owned by the screen coroutine scope. Leaving during an
unfinished operation cancels it and does not replay it on return or show a false
success. Cancellation is **not a rollback guarantee** if a DataStore transaction
already committed. The screen/repository rereads persisted state on return.
Document-provider writes can fail partway through; the UI states that a failed
export is not usable, and does not claim atomic SAF output or roll back a provider.

## Reopened actual-landscape defect (2026-10-27)

The earlier landscape statements below were **not evidence of a readable true
640×360 dp rotated dialog**. The original Robolectric fixture was 822×369 dp;
the first emulator size-swap attempt was letterboxed, not real landscape. Neither
established the short-height physical geometry. Semantic scrolling and an unclipped
text layout alone can pass while the parent viewport exposes only glyph fragments.

Independent review rejected integrated commit `0dd72508b400cd25a2de73ebb83674415288cd8e`
(APK SHA-256 `da2b56f0f8c4077a0a35c5ad73eaee14e46fe67af6cb47343668d74b00845f5f`),
despite 1,450 passing unit tests. Actual API26 rotation1 was 1280×720 px at320dpi;
the app was1184 px wide, with48 px top status and96 px right navigation insets.
At effective2.4× font, the platform-default640 px dialog wrapped the title into two
lines and left a38 px body viewport—less than one full line. Both the initial
irreversible warning and scrolled backup tail were unreadable while Confirm was
visible. The original frames and window receipts remain in the parent evidence
archive `native-emulator-evidence/0dd72508b400-ime-rerun/landscape-rotated-evidence`;
they are failed evidence, not retroactively approved screenshots.

The repair changes only this clear dialog's geometry: opt out of the platform
width, retain16 dp outer side margins and a560 dp maximum surface, preserve all
user font scaling, and expose separate scroll-viewport/text nodes for tests.
The full warning, selected-category request token, confirm/cancel callbacks and
single-flight mutation guards are unchanged. Regression checks use the conservative
592×336 dp safe window within640×360 dp physical landscape, plus320×640 dp narrow
portrait, in dark and light at2.4× font. They require every character box of the
initial irreversible sentence, and every backup-tail character after scrolling,
to fit the **clipped** body viewport; both actions must remain wholly reachable.
The final focused gate passed **18/18 tests** (4 geometry,11 mutation/SAF boundary,
3 root-navigation/appearance), no failures/errors/skips. At the592×336 dp safe
landscape window, the body viewport is1024×160 px and each full line is98 px:
the entire initial sentence fits at y268–366 px within y268–428 px, and the complete
backup tail fits at y330–428 px after scroll. Narrow portrait's body viewport is
480×314 px; both the two-line initial warning and scrolled two-line backup tail fit.
All measured text retains effective2.4× density. The independently reviewed actual
rotated compositor is still the acceptance gate, not these Robolectric numbers.

First attempts are preserved: two test-helper compilation failures (constructor /
DpRect API), then4 new test-only synchronization failures before the dialog existed;
all14 existing boundary/root cases passed that first executable run. Synchronizing
on the clear-title semantics before reading the separate dialog Window resolved
that test setup issue; no production code or visibility assertions were weakened.
The successful compile and fresh test process used the shared heavy-job lock and
1536 /1024 MiB Gradle heaps respectively, with the unchanged2 GiB test-worker heap.

Candidate source fingerprint:
`sha256:51d567605f8c94736edee326b0992b5f9b4f4aa43e915acd75cd0d25b9475e7a`.
Original focused logs/XML/8 PNGs (4 initial/tail pairs) and geometry
sidecars are archived in `native-blacklist-landscape-evidence/fourth`; prior failures
remain in `first`, `second` and `third`. Actual rotated emulator review and final
integrated full gates remain mandatory.

## Earlier scoped verification record

- `:app:assembleDebug :app:compileDebugUnitTestKotlin` passed on the initial candidate.
  The independently requested stale-action correction subsequently compiled.
- First focused gate: **28 tests, 1 failed, 0 errors/skips**. The sole failure was
  the new test's assumption that retaining Material's semantics `OnClick` retains
  an old callback. A still-mounted clickable node forwards that wrapper to the
  current handler, correctly accepting current draft `44`, not old draft `43`.
  The corrected expectation checks the accepted current value and excludes `43`;
  the source guard was not removed. This run remains a failed gate, not a flake or
  a retroactive pass. Original log/XML/frames were archived separately.
- Root tests in that run exercised `NativeApp` settings → blacklist → user/topic
  → Back under Robolectric, exact list position/draft preservation, and dark
  portrait / 822×369 dp landscape at system2× and app1.2×. Clear title layout
  confirmed effective2.4× density. Button/scroll assertions did **not** establish
  readable complete warning lines at the later real640×360 dp landscape geometry.
- Second focused gate: **28 tests, 0 failures/errors/skips**, including the corrected
  live-node semantics expectation, buffered duplicate result after restoration,
  cancel clearing stale success text, and short-dialog tail-scroll evidence.
- Independent pixel review then identified two further presentation issues: the
  irreversible warning was initially below the short dialog viewport, and the
  default 64 dp top app bar cut the effective 2.4× title at its bottom edge.
  The warning now leads the body; the local blacklist toolbar grows with title
  line height. New tests assert full title layout is within the toolbar, and
  sidecars include measured title/bar bounds. Initial and tail frames are recaptured.
- Third focused gate: **28 tests, 2 failed**, both at the newly added aggregate
  title `hasVisualOverflow` assertion. A diagnostic-only repeat reproduced those
  two failures and captured constraints, paragraph dimensions and original pixels.
  This was a width-allocation mismatch, not residual vertical clipping: the title
  node is 513×255 px, paragraph allocation is 850/2320 px wide, max height is
  270 px, and height overflow is false. The full title occupies y=2.15–80.62 dp
  inside an 83.08 dp toolbar. Independent review confirmed the complete glyphs.
  The baseline 64 dp toolbar was genuinely too short; that runtime fix remains.
- Earlier final focused gate: **28 tests / four nonempty suites, zero failures, errors or
  skips**. Compilation passed first in a separate 1536 MiB Gradle process; the
  focused test process used 1024 MiB with the unchanged 2 GiB test-worker heap.
  The corrected assertion checks no vertical overflow or ellipsis, actual line
  extents and every character rectangle inside the 513×255 px node, plus the
  node inside the toolbar. Character x-ranges are exactly 0–171, 171–342 and
  342–513 px. No typography/runtime change was made to hide the diagnostic flag.
- Earlier main-source fingerprint:
  `sha256:606c5893b9ac826f8346dcf94da9c5fa1171dadb180a3fc2a5fd5f8dbe72c44b`.
  Runtime source remains commit `558112615be113e913e0eac6bc01c3b93809cc14`;
  later isolated changes are test/evidence only. Initial/tail warning-first dialog
  frames and complete title metrics were regenerated on this exact source.
  The landscape actions frame intentionally scrolls the list to its export action;
  partially scrolled-off category chips are not toolbar overlap.
- Integrated full tests/lint and the user's independent actual-emulator UI approval
  remain separate parent-owned gates. These focused checks are not that approval.

Synthetic Robolectric capture directory: `app/build/verification/blacklist-parity`.
Each frame has a JSON sidecar recording the main-source fingerprint and zero fixture
account HTTP calls. SAF callback tests use the real ActivityResult contracts with a
synthetic registry and in-memory document access, **not Android DocumentsUI**.
Physical-device/TalkBack/IME/provider coverage and live network acceptance are not
implied. The parent owns integrated full tests, lint, actual emulator review and
publication; this isolated patch does not itself satisfy those gates.
