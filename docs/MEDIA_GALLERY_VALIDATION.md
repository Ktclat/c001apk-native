# Ordinary media gallery validation

## Scope and provenance

This change adds distinct loading, success and error states to ordinary media thumbnails and the production full-screen gallery. Each thumbnail remains one whole-cell “查看原图” entry. A failed viewer offers a single explicit “重试本图” action. Loading and failed images do not automatically retry. State ownership includes the image occurrence, current list, user attempt and request mount lifetime, so duplicate URLs and late completions cannot finish a different page or newer request.

The existing image-loader injection boundary now reaches the real Dialog. Explicit retry bypasses memory/disk reads only for that image request, while permitting repair of its normal cache entry. It never clears the global cache. The bottom action layout can wrap for compact large-font windows; existing save/share/transfer behavior is unchanged.

No changes were made to MediaTransfer, DownloadManager, ArticleMedia's stricter policy, authentication or permissions. Normal four-image 2×2 and nine-image three-column arrangements remain; only insufficient widths reduce columns to preserve 48dp whole-cell targets. Extreme-wide single images retain their image geometry inside a minimum-size touch region.

Implementation base: `080a4fd6b84fb3d3937ba8ea3550e68dd637c32c`, tree `682510854375161afea934a2a948313c364e6840`.

The executed v9 snapshot contains 370 source/test/fixture files. This validation note is the sole post-run documentation addition; it does not change any executed input. Production bytes have remained unchanged since candidate v3. All 370 executed files matched their frozen SHA-256 manifest before and after the final run.

## Final focused result

On 2026-10-23, the offline Gradle invocation completed successfully in 5m42s: **47 tests passed, zero failed or skipped**, across eight classes:

| Class | Tests |
| --- | ---: |
| MediaImageStateTest | 7 |
| MediaGalleryStateTest | 10 |
| MediaGalleryCorruptCacheTest | 1 |
| NativeGalleryRootTest | 17 |
| MediaGridLayoutTest | 4 |
| MediaGridRenderingTest | 4 |
| NativeFeedCardTest | 3 |
| FeedReplyRowTest | 1 |

The test task was `:app:testDebugUnitTest`, filtered to those classes, with `--offline --no-daemon --no-parallel --max-workers=1`, JDK 17, isolated HOME/XDG directories and ANDROID_USER_HOME. No APK was installed. This is a **focused matrix, not a full unit/lint/assemble gate**. Full repository CI remains required before release acceptance.

Covered behavior includes hanging loads; success and failure; repeated explicit retry failure; failed-page revisits without automatic retry; actual pager disposal and re-entry; late callbacks from old pages and mounts; list replacement/shrink/empty cases; close/reopen ownership; duplicate URLs at separate ordinals; explicit loader propagation; the production NativeApp/NativeFeedCard/FeedReplyRow entry → full-screen Dialog → Back loop; 4/9/>9 image layouts; compact, extremely narrow, landscape and actual system fontScale 1.6 conditions.

The root fixture waits for production body text to finish layout before remembering the source position. It then checks entry/body/text clipped and unclipped bounds, viewport bounds, list index/offset and no home refetch after Back. Sixteen final Back captures preserve these recorded geometries. The reported-window feed before/Back PNGs are also pixel-identical. Save/share buttons are inspected for layout only; their external actions are not invoked.

## Real image data and captured pixels

The test fixture is original deterministic geometric artwork, not a downloaded or third-party image:

- `app/src/test/resources/gallery/original-landscape.png`
- 240×180 RGB PNG, 1,508 bytes, four colors
- SHA-256 `e4188583fad6e7d5d2ffefe944def82b36b868f4112389c9b48e9a403dd7347a`

Controlled UI results read raw PNG bytes and require successful Android BitmapFactory decoding. A separate test uses the real Coil HTTP fetcher, disk cache and decoder with a memory-only OkHttp interceptor: an HTTP 200 response containing corrupt PNG data is verified in disk cache, explicit retry reads valid PNG bytes, repairs that cache key, and leaves an unrelated sentinel entry unchanged. The interceptor never proceeds to a real socket.

The final run produced **68 original PNG captures**, each independently parsed and fully decoded. Viewer captures draw the sole shown production Dialog's actual window decor, never the underlying Activity or a simulated viewer. Matching metrics record window, content, target and text geometry. The baseline Canvas-derived bitmap images serve only as UI-state baseline evidence, not raw-PNG decode proof.

## Actual large-font verification

Earlier v5 screenshots labeled with requested fontScale 1.6 did not establish that the Dialog actually used that scale. **The v5 large-font conclusion is withdrawn.** Its ordinary functional evidence remains separate.

The final fixture configures Robolectric's public system font scale before Activity composition and Dialog creation. It observes Activity/Application resources, actual root density and each real viewer TextLayoutResult's density/fontScale. It does not insert fake LocalDensity into production Dialog content.

For matching compact and landscape captures, the final measured viewer fontScale changes from 1.0 to 1.6. Back, previous, next and retry text have actual font size 28.0 → 45.6px; the first glyph grows from 28.1×41.0 → 45.157894×68.0px. Compact bottom actions visibly wrap while remaining usable. Complete label coverage, no ellipsis, line count/height, each line and glyph fitting the actual text size, clipped/unclipped text/button bounds, viewport visibility and 48dp targets are checked.

Pinned Compose 1.7.6 reconstructs the semantics TextLayoutResult paragraph at the parent's maximum width while retaining the actual narrow text layout size. Consequently, its generic `didOverflowWidth` can be true even when all actual glyphs fit. Current diagnostic evidence for “返回” records a 92×69px text layout, 91.5px intrinsic width, line right edge 90.32143px, both complete glyphs inside that size, and a wider 1108px reconstructed paragraph. The final test replaces only that misleading width boolean with the stricter direct geometry and completeness checks described above; height/max-lines, physical target sizes and safety bounds remain enforced. Raw diagnostics and the original failing assertions are preserved in the validation evidence.

## Reported-window mapping and its limits

The generic synthetic environment maps the reported physical host/real display to **1200×2670px at density 3.25**, corresponding to 369.230769×821.538462dp. It supplies independent status/view-padding top 140px and system-gesture top 179px simultaneously, with bottom and IME insets zero. Those top regions are not added together.

The production dependency's natural Dialog is **1199×2672px, not 1200×2670px**. Compose 1.7.6 derives Dialog size using `round(Configuration.screenWidthDp/HeightDp × density)`. The fixture's current Android integer configuration is 369×822dp, giving that exact natural result. The integer configuration is synthetic, not a measurement of the user's native Android Configuration. Tests record actual resource/display/window metrics and assert the pinned sizing rule with zero tolerance; they do not stretch, remeasure or change the Dialog to hide the difference.

Production DialogProperties retains declared `decorFitsSystemWindows=true`. The API 35 window with target SDK 36 reports effective false because the platform enforces edge-to-edge. Before/after/capture policy observations match; the fixture neither overrides the Dialog flag nor adds synthetic content padding. The normal DecorView inset handler is called and its outputs are recorded.

Actual window-coordinate controls remain at least 48dp, wholly below status top 140px, with synthetic tap centers below the separate gesture top 179px, and inside both the actual Dialog and physical host bounds. This is a **synthetic tap/visibility check, not proof of physical gesture safety or a real-device reproduction**. The source log's accessibleNavigation/disableAnimations values are recorded as observations, not used to override production navigation hiding or accessibility behavior. No Xiaomi/device-specific production branch was added.

## Baseline and retained diagnostic history

- Baseline: two compatible production-root tests passed. They show the previous infinity thumbnail marker and blank failed viewer. The initial baseline body was still pending layout, so its +8px entry shift on return cannot prove equal source geometry. Final tests wait for body readiness and enforce equality.
- v3: test compilation failed on unsupported DpRect width/height properties; zero tests executed. Replaced by equivalent right-left/bottom-top calculations without loosening thresholds.
- v4: not run; source review rejected a Dialog fixture flag override.
- v5: 44/47 passed. Two failures occurred before first Dialog request assertions due to missing first-frame synchronization; one came from incorrectly equating the declared Dialog policy with the platform's effective policy. The requested-only large-font evidence was later invalidated.
- v6: the two first-frame/ownership cases passed; the remaining exact-window test exposed the natural 1199px Dialog width rather than the incorrectly assumed 1200px.
- v7 and v8: each one-test diagnostic retained the generic text-width overflow failure. v8 captured actual text/paragraph/glyph geometry and original Dialog pixels before failure, allowing the strict replacement above to be reviewed against current evidence.
- v9: the complete focused 47-test matrix passed, including actual fontScale 1.6, natural Dialog geometry, safety bounds, real decode/cache recovery and source restoration.

No failure record was relabeled as a pass. Logs, XML, immutable source manifests, raw PNGs and metrics remain separate by run. Live network behavior, physical-device rendering/gestures, real save/share operations and full repository CI are not established by this focused result.
