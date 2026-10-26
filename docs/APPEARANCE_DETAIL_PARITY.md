# Appearance detail pickers

## Reference and bounded scope

The Flutter reference is `0d9ed002980d935c1c9351393e9f16784aa78903`:

- `components/settings/font_choice_tile.dart`: a dedicated font comparison picker; each sample uses its own font rather than the active app theme's font.
- `components/settings/reading_preset_tile.dart`: a dedicated reading picker, using each preset's metrics and the current chosen font.
- `components/dialog.dart`, `SliderDialog`: a local font-size draft, committed by a deliberate confirmation rather than every slider movement.

The native baseline is `99b722d0ba2d3bd6f6f1ef5b3592a223dde2a486`. Its appearance controls already had real persistence and real rendering consumers, but the settings rows lacked these detailed comparison flows. The other concrete gaps—official device identity/manual protocol controls, official emoji rendering and separately scoped blacklist operations—remain outside this appearance-only slice.

## Implemented behavior

`设置 → 外观与字体` now opens three real, scrollable modal detail views:

1. **界面字体** shows independent samples for system, Material and bundled Noto. Choosing a radio row updates only `font` through the existing persisted field-transform intent. System and Material can look identical on some Android devices; the screen explains this rather than inventing a visual difference.
2. **正文排版** shows actual title, body and reply styles for both presets. Samples use `readingStyles`, the same function used by the existing content readers, and the currently selected font. Selecting a row updates only `readingPreset`.
3. **应用字号** previews an isolated draft. The preview replaces only the app-scale factor of the existing effective density, retaining system scaling. Reset changes the draft to 100%; Apply emits one `fontScale` field intent. Cancel, Close, swipe dismissal and dialog Back do not commit. Existing 80–160% native values and 5% steps are retained; Flutter's narrower 80–130% range is not silently imposed.

The parent Settings list remains composed under each modal, retaining its category and scroll position. The close action stays outside the scrolling content. Font and reading selection close immediately, matching Flutter; durability/errors continue through the existing Settings repository and NativeApp error handling, not optimistic success banners. The font-size draft and open detail survive saved-instance restoration; unrelated setting emissions do not replace the draft or get overwritten by Apply.

No global routes, network dependencies, account credentials, device identifiers, privacy controls, dock motion, read verification, or image URL behavior are changed.

## Verification

The final focused source `59d651b42caac30b42e43d1652f9bd70034c51bc` (tree `3b6a80816491209b821c07375d5ad3b4211fab5c`) compiled all production/test Kotlin and passed **64 tests across 15 suites, zero failures/errors/skips**. All frozen tracked-file hashes matched afterward. The runtime fingerprint is `sha256:d58d5b9ec27d482e3889b87a67afdb1c50cabc94c9ea25fa7fbb820b56d7b579`. Evidence is archived outside the checkout in `settings-verification/focused-v4`, including logs, XML, original PNGs, per-modal JSON and SHA-256 manifests.

Coverage includes actual measured font families, preset metrics, density scaling, draft dismissal and restoration, rapid Apply single-write behavior, field-intent preservation, disk-store recreation, exact nonzero parent scroll retention and actual NativeApp detail routes. Native-graphics captures draw the separate modal **Dialog window**, not the obscured Activity. These synthetic fixtures do not substitute for physical-device, TalkBack, real-account or service validation.


### Findings closed before the passing gate

- The first gate compiled production but failed test compilation because the new test imported the experimental, non-JUnit `StateRestorationTester`. The repository's existing JUnit API was restored; that attempt executed no tests.
- The next gate passed 60/64. The new effective-density assertion exposed that `ModalBottomSheet` installs its Android-window density rather than retaining the host's app scale. The sheet now explicitly re-provides the captured host density inside the window. The unchanged regression assertion verifies system 2× × draft 1.6 = **3.2**, even when the saved app scale is 1.2.
- That gate also exposed lost dialog-local draft state on saved-instance restoration and a 40dp close icon. Draft state now lives in the parent Settings saveable owner and resets from the saved value only on a new open; close and commit/cancel controls have explicit 48dp minimum dimensions.
- The third gate passed 63/64; the new scroll fixture was tall enough not to overflow. The final fixture uses a short 420dp viewport, asserts a positive parent scroll **before** opening, then checks identical scroll and anchor after Cancel. The nonzero and exact-return requirements were not removed.

The six new actual-dialog captures cover portrait font/preset/size detail and short landscape dark 2× system font. An initial multi-image review impression that two short-dark Close icons were missing was incorrect. Individually reopening the original frames clearly shows the upper-right X; no image or code change was needed. The reading-frame SHA-256 is `3aebc7650eff0c6b5f0adb26305200f487d8a18463ca0dfde63eb592162fd1b2`; the size-tail SHA-256 is `a690d7fbdbe6ed03d7081d11c9ea369593aadcc85cc824f544c2681f20b005bf`. Both are byte-identical between v3 and v4. Final combined APK assembly/lint/full-suite validation and the API26 candidate tour (including visible Close, Apply/Cancel and restart persistence) are integration responsibilities and are not claimed by this focused gate. Independent pixel review is recorded separately.
