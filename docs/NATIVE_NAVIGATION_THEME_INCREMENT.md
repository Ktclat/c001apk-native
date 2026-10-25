# Native navigation and Flutter color alignment increment

This candidate is based on native `4564943` (plus the existing results-only
status documentation). It does not change account/session ownership, transport,
credential storage, Settings serialization, root destination order, or the
standard navigation-bar / rail geometry.

## Floating navigation

- Real consumed vertical scroll moves the floating dock **down continuously,
  pixel for pixel**, instead of removing it at a 28 dp threshold. Downward
  translation and opacity follow the same normalized progress as Flutter's
  `FloatingDockMotion`; actual dock height, accessibility font size, bottom gap
  and remaining gesture-safe inset determine travel distance.
- Direction reversal follows the finger immediately. Genuine user flings may
  continue the movement; unrelated programmatic scroll, horizontal flings,
  invalid deltas and unrelated unconsumed edge drags do not hide/reveal it.
  A genuine reverse gesture at the current root's measured top explicitly
  reveals it, even when fewer than 20 dp remained or a top-edge pull consumes
  zero pixels. Programmatic top repositioning alone does not reveal it.
- At rest, a bounded 60–180 ms settle uses Flutter's 28 dp hide / 20 dp reveal
  intent thresholds, otherwise the nearest end. A zero-consumption edge attempt does not interrupt an active settle. A new
  consumed gesture resumes
  from its current position, and root/account/mode/keyboard/search changes
  discard old state and cancel old settling. There is no half-hidden active
  navigation left at rest. The active root's real scroll-state completion also
  settles accessibility `ScrollBy` actions, which need not dispatch a nested
  fling callback.
- Android's reduced-motion preference retains snap-at-threshold behavior,
  without decorative travel. Existing accessibility recovery remains on labeled
  toolbar/scroll controls; a partly hidden dock is also recoverable. Partly
  clipped tabs reject touch/focus actions and are excluded from semantics.
- Horizontal dragging **inside either floating or standard bottom tab rows** selects the tab currently
  under the finger, including reversals and RTL. Selected-tab/callback updates
  do not restart the active gesture. Repeated movement within one tab does not
  trigger repeated selections or scroll-to-top. Taps/reselection and semantic
  selection remain unchanged; vertical gestures do not select tabs.
- The overlay's reserved tail space is unchanged while hidden; controls at the
  end of root lists remain reachable. Translation is clipped to the overlay
  viewport rather than drawing into platform gesture space.

The former cross-gesture binary accumulation tests are intentionally updated:
short independent drags now settle fully shown rather than carrying stale intent
into a later drag. Reduced-motion tests retain explicit threshold coverage.

## Colors

See [Flutter fixed-palette parity](FLUTTER_THEME_PARITY.md). All 1,368 exported
fixed-palette role values match the pinned real Flutter implementation. Existing
seed indexes/defaults are unchanged. Platform dynamic colors remain enabled
when the existing preference/Android version selects them; universal ROM/plugin
harmonization parity and whole-app component-style parity are not claimed.

## Verification scope

New deterministic tests cover progress, reversal, intent settling, current
travel extent, programmatic/horizontal/invalid input exclusion, reduced motion,
RTL hit mapping, held-pointer scrub across component recomposition and actual
NativeApp root transitions, disabled targets, vertical/cancel gestures, and
actual-root pixel-linked in-flight geometry. Existing navigation, accessibility,
insets, settings and security suites remain part of the full gate.

Gradle/Robolectric frames are **synthetic** evidence, not Android device,
TalkBack gesture, refresh-rate/FPS, live account/API, or full Flutter parity
acceptance. Test logs/results and exact-source manifests are recorded separately
for the completed gate; compilation or a focused diagnostic run is not a full
pass. The vertical rail retains its existing click/keyboard interaction. No push,
merge, install, credential operation or live-account mutation is
included in this candidate.
