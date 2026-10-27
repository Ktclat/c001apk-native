# Appearance-sheet system-bar contrast

## Actual running-UI failure

The legacy MainActivity scrim repair was necessary but did not cover a separate
Material3 modal window. On production commit
`0b5cc7d2b6c5cd993ea3440c38b153d2110f57c3` (APK SHA256
`63431778ff44457358877d1b22a65db8fd54bc77856637d87bc2c45c345bc3df`),
independent review of the **active Font sheet** found dark status/navigation
glyphs on a dark background. MainActivity after dismissal was correct; testing
only that parent window missed the modal-window failure.

The unchanged actual emulator screenshot is
`native-emulator-evidence/0b5cc7d2b6c5-final/production-theme-evidence/production-theme-06-font-dialog.png`,
SHA256 `a9512c57f99b52c28ef18703afdba5c4afa3e72270a495772e43eb885e245cfb`.
The dominant navigation background is RGB(29,27,32), with near-black glyph pixels.
A separate active-window dump identifies the focused dialog as type 2 with
`mSystemUiVisibility=0x2710`: both LIGHT appearance bits are set, requesting dark
icons. The underlying Activity is a distinct window.

## Pinned cause and narrow correction

Exact cached `material3-android:1.3.1` AAR SHA256:
`752473d4ea271f822858c313ec0d857ab05249021906f3005b30f2b1fcd0dcc0`.
Bytecode inspection shows `ModalBottomSheetDialogWrapper` initializes its two
icon flags from **system** night mode, not the application-resolved MaterialTheme.
Its constructor runs before sheet-content composition. Later `updateParameters`
does not reset the icon flags. The pinned `ModalBottomSheetProperties` has no
per-bar appearance options. The public `DialogWindowProvider` is implemented by
the modal content root's parent. An official AndroidX source snapshot documents
[the same system-theme initialization and dialog-window ownership](https://android.googlesource.com/platform/frameworks/support/+/a24d533ebe4a5288dd8ae9d6b862ca7703708941/compose/material3/material3/src/androidMain/kotlin/androidx/compose/material3/ModalBottomSheet.android.kt).
The exact pinned bytecode, not that source snapshot's version assumption, is the
basis for this correction.

Only the shared `AppearanceChoiceSheet` changes, covering Font, Reading and
Scale. It explicitly reuses the existing default container color, derives icon
polarity from that actual resolved color, and applies the two appearance flags
in a sheet-content `SideEffect` to the **sheet's own DialogWindowProvider window**.
There is no Activity-window fallback. This follows fixed/dynamic/system-resolved
colors without changing a saved preference or fabricating OS configuration.

The sheet's transparent bar colors are retained; MainActivity's legacy scrim
helper is deliberately not reused. No contrast enforcement, secure policy,
cutout, layout, gesture exclusions, IME/back handling, dependency version or
manifest permission is changed. Dismissing the sheet disposes its own window,
so no parent-window restoration handler or retained window reference is needed.

## Causal tests and scope

The unchanged focused group produced **RED 38 tests / 18 expected failures**,
then **GREEN 38 tests / zero failures, errors or skips**. All 18 RED failures
were precisely opposite active-sheet status-icon polarity on SDK26/29/35; the
20 existing appearance and MainActivity bar checks already passed. GREEN also
executes the navigation-icon assertions and complete interaction/state checks.

The test-source SHA256 is identical in both runs:
`b4559dbef414bf2721d1ed31eb208516d81f5659f800b3e5c24b56f96b1ff7e2`.
The production sheet file was byte-identical to 0b5cc7d before the change
(`c6a1287f9b7ea4af62453d368405537f8b2df6283433ace7ee0d7015fae623a2`),
and is `ecb68f0ee020ad0ade260ea41269b5e1ded715e4733d6f19db1959eaca2d9d93`
afterward. Before/after hashes within each run are unchanged. Logs and JUnit XML
are archived under `native-modal-system-bar-evidence/{red,green}/`, with
`red-green-receipt.json` binding their counts, cases and hashes.

`AppearanceSheetSystemBarTest` opens the real production SettingsScreen sheets
and inspects the **showing dialog window**, not the Activity. On SDK26/29/35 it
covers all three sheet types, forced Dark on a light system, forced Light on a
dark system, System mode, repeated live fixed-seed/dynamic color changes, and
preservation of parent appearance plus dialog bar colors, secure/window flags,
contrast, layout, cutout, IME mode and gesture state. Close/Back/Scale Cancel
must not persist a draft; save-state restoration creates a new correctly styled
window while retaining the unsaved scale draft. Existing appearance interaction
and MainActivity system-bar regressions remain in the focused group.

The dialog policy snapshot is taken after first open, so its comparisons establish
no later policy mutations. Initial policy preservation is separately established
by the exact flags-only production diff and the unchanged pinned Material3 theme,
which specifies transparent status/navigation colors; it is not inferred from
that post-open snapshot alone.

These are synthetic window-state tests, not compositor or physical-device
approval. Root must run the full final-source gate and build a new exact APK.
Independent review must inspect original full compositor frames **while Font,
Reading and Scale sheets are active in Dark and Light**, plus cancellation,
recreation and return-to-Main behavior. The preceding APK's passing 1,432-test
gate does not approve this new APK or erase the active-sheet finding.
