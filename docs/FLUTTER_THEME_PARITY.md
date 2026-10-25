# Flutter fixed-palette parity

The native fixed-color path now uses **actual Flutter-generated colors**, not
an RGB interpolation or an approximate HCT port. Its 19 stored color indexes and
seed values are unchanged. Both light and dark schemes cover all **36 public
color roles in Compose Material 3 1.3.1**: accent/foreground/container roles,
seed-tinted neutral surfaces, inverse roles, error roles, outlines and scrim.

## Pinned reference

- Flutter **3.47.7**, framework `abaf9c523780a608bd46686fd5e53740a07077f8`.
- `material_color_utilities` **0.13.0**.
- c001apk Flutter reference `2aa5348790778c229f8fed647d2b92aae1143d74`:
  `apps/flutter/lib/constants/constants.dart` supplies the seeds;
  `apps/flutter/lib/main.dart` calls `ColorScheme.fromSeed` with only seed and
  brightness. Therefore the reference uses **tonalSpot, contrast level 0.0**, not
  an expressive scheme or another unselected variant.
- `app/tools/flutter_palette/export_flutter_palettes_test.dart` executes the
  real Flutter API and checks native seed order against Flutter constants.
  It checks the pinned Flutter revision/package lock and SHA-256 of both source
  files before producing data; any future theme variant/call-site change fails
  until manually reviewed. Source hashes:
  - `main.dart`: `6df4e2ea7c462a83224aeaf18dd669342699ba30922cc47b84408d980a05ab4c`
  - `constants.dart`: `c63757ece40b46bcff66ad6535026953cf145d2d0bdd694acb8badc7dba280e4`

`FlutterSeedPalettes.kt` is the generated runtime table. The independent-format
`app/src/test/resources/theme/flutter-seed-palettes.tsv` records **1,368 exact
ARGB values**. `FlutterThemeParityTest` checks every native role against this
fixture, coverage/duplicates, seed-derived neutrals, valid stored indexes,
rejected invalid indexes, and unchanged theme defaults. Existing
`ThemeTypographyTest` keeps foreground and outline contrast checks for all
38 schemes.

## Reproduce / update

Use a prepared checkout of the pinned Flutter reference app, with its existing
locked dependencies. No Flutter or Dart runtime is needed to build native once
the generated files are committed. From that Flutter app's directory:

```sh
export FLUTTER_ROOT=/absolute/path/to/flutter-3.47.7
export NATIVE_ROOT=/absolute/path/to/c001apk-native
# Read-only verification of both checked-in generated outputs:
"$FLUTTER_ROOT/bin/flutter" test --no-pub \
  "$NATIVE_ROOT/app/tools/flutter_palette/export_flutter_palettes_test.dart"
# Explicit regeneration when intentionally refreshing the reference:
UPDATE_NATIVE_PALETTES=1 "$FLUTTER_ROOT/bin/flutter" test --no-pub \
  "$NATIVE_ROOT/app/tools/flutter_palette/export_flutter_palettes_test.dart"
```

Review palette changes and run the native suite after regeneration. A future
Flutter SDK/package update or theme-variant change requires updating the pinned
exporter intentionally; do not silently rerun against a different reference.
No third-party algorithm source is copied or bundled into the native app.

## Scope and limits

- Light/dark/system preference, all saved seed indexes, dynamic-color switch,
  font preferences, account state, and storage validation are unchanged.
- On Android 12+ with dynamic color enabled, the existing **platform dynamic
  scheme** remains selected. The fixed tables apply when dynamic color is off
  or unavailable. This does **not** establish byte-for-byte parity with Flutter's
  device-dependent dynamic-color plugin / harmonization on every Android ROM.
- Flutter's additional fixed accent roles and shadow role are not exposed by
  Compose 1.3.1's `ColorScheme`; no unused fake substitutes are introduced.
- This establishes fixed **palette-role** parity, not whole-app screenshot,
  component-style, typography, device, or full feature parity. Native and Flutter
  widgets can still choose different roles/elevations. No new MD3 Expressive
  picker or implicit preference override has been added.
