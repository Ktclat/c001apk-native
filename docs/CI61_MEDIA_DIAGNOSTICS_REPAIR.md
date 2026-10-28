# CI 61: diagnostics and gallery failure assertions

Source-only follow-up to GitHub Actions run 40639669858, exact remote input
`5142324ee1d8ade29155c4eb27617ab9139ad181`. That run remains failed:
1510 tests, 12 failures, zero errors/skips. This delta does not rewrite its result
or claim that a new run has passed. It is based on local integrated `7a955da`.

## Evidence read before the change

- `NativeDiagnosticsTest.reportIsAnExplicitNonSensitiveAllowlist`: JUnit says
  expected 23 entries, actual 25. The recovered production report already adds
  public-image observation scope and bounded aggregate output.
- Seven `NativeGalleryRootTest.exerciseFailure` callers stop at line 304's
  `dialogText("图片加载失败").assertIsDisplayed()`. Each uses the same controlled
  IOException without fetch/decode phase evidence. Current production displays
  the sanitized ambiguous reason `图片读取或解码失败` instead of that old label.
- The same run's `verification/gallery/root-exact-reported-dialog-error.png`
  visibly shows this reason with retry/back. It belongs to a separate passing
  scenario; it is not represented as a screenshot of the seven failed cases.
  Those cases stopped before their dialog capture. Extracted image SHA256:
  `8342e28947a4cdae0992d5201104b02645073babe5619b213762e4c10e207a62`.
- Original logs/XML/archive remain in the local publication evidence. No local
  Gradle/emulator/session access was used for this investigation.

## Correction, without changing production behavior

The diagnostics test now pins all 25 ordered names, not just a count, retains the
sensitive-field exclusions, checks both unavailable fields and no-record caveats,
and checks exact closed public-image export and instance-field types.

The gallery helper now requires the specific reason tag and exact expected text
for this IOException fixture. It checks complete lines/glyphs, actual font/density,
unclipped bounds inside the image page and Dialog, and no overlap with retry.
Existing button 48dp, text/glyph, no-overlap, paging, root navigation, source-scroll
retention, no-back-retry and exactly-one-explicit-retry checks are unchanged.
The existing paragraph/glyph checks were extracted unchanged for sharing; reasons
are not incorrectly treated as 48dp action targets. Successful retry must remove
both the retry button and failure reason. Dialog capture now precedes the reason
check so a future geometry failure retains visible evidence.

No test is skipped, blanket-matched or changed to accept multiple unrelated
failure reasons. No additional production label is added merely to satisfy an
obsolete string, which would unnecessarily alter narrow/large-font layout.

## Current validation

`git diff --check` and lightweight source comparisons pass, including unchanged
original paragraph/glyph assertions and exact retry/back request-count assertions.
This new delta has not been compiled or executed; independent CI and runtime
review remain required. These fixture results cannot establish live-CDN recovery.
