# Legacy navigation scrim follows the app theme

## Confirmed defect and narrow correction

The independently reviewed API26 emulator run of **unmodified production
MainActivity at c287262456cd762fb0c8e07857c713618fe99183** reproduced a real contrast
failure: choosing **深色** under Settings → 外观与字体 made the application dark
and the Android three-button navigation glyphs light, while their background
remained very light gray. Returning to **浅色 / 系统** restored readable dark
navigation glyphs. This was not limited to an injected test Activity.

Before originals (720×1280, unchanged compositor captures):
- `native-emulator-evidence/c287262456cd-run3-fresh/production-dark-manual/06-appearance-system-light.png`,
  SHA256 `b9da4abcba69b3898e461ff1d92408458ab17f8ca26ebd5fae6a7c282ca3ba7c`.
- `.../07-forced-dark.png`,
  SHA256 `d62c5d7151665a2d5166b54b0dd1d5299e7fcd36401deb704f8725cc9d708211`.
  Its dominant bottom-bar pixel is RGB(232,232,232), against nearly white glyphs;
  sampled application surface is RGB(20,18,24).

The original `enableEdgeToEdge()` startup selects a navigation scrim from system
resource brightness. The later app-theme helper updated icon appearance but not
the legacy navigation background. The correction updates only API26–28
`navigationBarColor`, using the **same two AndroidX defaults** with the same
already-resolved `dark` Boolean as the icon flags. It does not change theme
selection, palettes, dynamic-color behavior, manifest/permissions or persistence.

## Dependency evidence and platform boundary

The exact resolved AndroidX artifacts were inspected with JDK `javap -p -c`:

| Artifact | AAR SHA256 |
|---|---|
| activity 1.9.3 | `7e45d2023dc17d715ffff3dc5792f798642c4ae11c948006b1752ab9edda5707` |
| core 1.13.1 | `2c27de199535675005553066597a4b20fa1eea7c228ab4ef6b32b5fe39ca1f59` |

Activity's `EdgeToEdge.kt` default scrims are light `#E6FFFFFF`, dark `#801B1B1B`.
Its API26 branch writes the selected scrim; Core's appearance setter does not.
Activity's API29+ automatic style instead uses transparent navigation color and
platform contrast enforcement. The repair deliberately leaves that newer
navigation color and policy untouched, as well as status-bar background,
cutout/layout, system-bar behavior and gesture exclusions. It does **not** disable
platform protections to force a screenshot appearance. The behavior boundary is
also described in the official [SystemBarStyle.auto documentation](https://developer.android.com/reference/androidx/activity/SystemBarStyle#auto(int,int,kotlin.jvm.functions.Function1)).
No dependency is upgraded in this correction.

## Focused verification

The same 14 SDK-sliced tests were run before and after the five-line production
change, with identical test-source SHA256
`4c79e9f97bcbbad679aabcf907dd6ebc7f7d5e7c1ef933bb9935b8673de62da6`:

- **RED:** 14 tests, exactly 6 failures, zero errors/skips. Only the three
  navigation-scrim transition cases on API26 and API28 failed; all API29/35 cases
  passed. Production `MainActivity.kt` was byte-identical to c287262
  (SHA256 `65bbfefdae9a210080c733d8eea55e2b5dfe3247898020abf2011d452eb1c197`).
- **GREEN:** 14 tests, zero failures/errors/skips, after only the helper change
  (SHA256 `9d89bb7522fe68a9d0256588841583d1cc53dcc58db8ea91a04824d09a2873e3`).
- Logs, unchanged-before/after source hashes and JUnit XML are archived in
  `native-system-bar-evidence/{red,green}/`; `red-green-receipt.json` binds cases
  and XML hashes. No other production file changed during these focused runs.

The expanded `SystemBarAppearanceTest` covers API26/28/29/35: light startup with
forced Dark and return, dark startup with forced Light and return, repeated calls,
and resource night-mode changes without another edge-to-edge initialization.
API29/35 sentinel cases additionally ensure navigation/status colors, both
contrast flags and gesture exclusions are preserved. All cases guard window flags,
layout appearance-independent bits, system-bar behavior and cutout policy.

These are synthetic Window-state assertions, **not** actual compositor or
hardware acceptance. The parent's exact-final-source full test/lint/build gate,
new-APK theme-toggle originals, and independent running-UI approval are still
required. The old c287262 full gate is not a pass for the repaired APK.

## Runtime acceptance scope

On the exact new API26 production APK, repeat Dark → Light → Dark → System, then
leave/reenter Settings, open/dismiss an appearance dialog, background/return and
relaunch with persisted Dark. Check readable navigation and status glyphs in the
original compositor captures, unchanged content/dock bounds, dynamic-color
fallback toggles and a fixed-seed change. Recheck the original dark large-font
rich-list finding. No app-data clearing, uninstall, security-setting change, or
parallel emulator boot is part of validation. Actual API29+ gestural/three-button
and device-dependent dynamic-color behavior remains unclaimed unless separately
run; synthetic API29/35 policy tests are not substituted for it.
