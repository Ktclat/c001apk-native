// SPDX-License-Identifier: AGPL-3.0-only
package io.github.ktclat.c001apk.nativeapp.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.KeyEvent
import android.view.View
import android.view.Window
import android.content.Context
import android.view.accessibility.AccessibilityManager
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.ComponentDialog
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.systemGestures
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import io.github.ktclat.c001apk.nativeapp.AppContainer
import io.github.ktclat.c001apk.nativeapp.account.*
import io.github.ktclat.c001apk.nativeapp.data.*
import io.github.ktclat.c001apk.nativeapp.domain.*
import io.github.ktclat.c001apk.nativeapp.feature.media.ControlledGalleryImages
import io.github.ktclat.c001apk.nativeapp.feature.media.LocalMediaImageLoader
import io.github.ktclat.c001apk.nativeapp.local.*
import io.github.ktclat.c001apk.nativeapp.settings.*
import io.github.ktclat.c001apk.nativeapp.social.SocialRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowDisplay
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog
import java.io.File
import java.io.IOException
import java.lang.reflect.Proxy
import java.util.concurrent.CopyOnWriteArrayList

/** Actual NativeApp home -> production MediaGrid -> production Dialog ImageGallery -> same home.
 * The account and repository are offline fixtures; no request reaches a socket. Captures draw the
 * actual shown Dialog.window.decorView, never a standalone gallery or the Activity behind it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w393dp-h852dp-420dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NativeGalleryRootTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private lateinit var images: ControlledGalleryImages
    private lateinit var client: OkHttpClient
    private val requests = CopyOnWriteArrayList<String>()
    private val blocked = CopyOnWriteArrayList<String>()
    private val homeState = LazyListState()
    private var density = 1f
    private var fontScale = 1f
    private var originalSystemFontScale: Float? = null
    private var actualRootFontScale = Float.NaN
    private var sourceKind = ""
    private var sourceCount = 0
    private var sourcePosition: Pair<Int, Int>? = null
    private var sourceEntryTag: String? = null
    private var sourceGeometry: Map<String, Rect>? = null
    private val sourceBodyText = "真实首页图片入口"
    private data class ExactViewport(
        val widthPx: Int, val heightPx: Int, val density: Float,
        val statusTopPx: Int, val gestureTopPx: Int, val bottomPx: Int = 0,
    )
    private var exactViewport: ExactViewport? = null
    private val injectedInsetObservations = mutableMapOf<String, Triple<Int, Int, Int>>()
    private val decorAppliedInsetObservations = mutableMapOf<String, Triple<Int, Int, Int>>()
    private data class WindowPolicyObservation(
        val declaredDialogDecorFits: Boolean?, val effectiveDecorFits: Boolean,
        val edgeToEdgeEnforced: Boolean, val targetSdk: Int,
    )
    private val windowPoliciesBefore = mutableMapOf<String, WindowPolicyObservation>()
    private val windowPoliciesAfter = mutableMapOf<String, WindowPolicyObservation>()
    private val exactWindowDecorViews = mutableListOf<View>()
    private var rootComposeInsets = Triple(-1, -1, -1)

    private class MemoryLocal : JsonStateStore<LocalState> {
        override val data = MutableStateFlow(LocalState())
        override suspend fun update(transform: (LocalState) -> LocalState) { data.value = transform(data.value) }
    }

    @After fun close() {
        if (exactWindowDecorViews.isNotEmpty()) compose.runOnUiThread {
            exactWindowDecorViews.forEach { ViewCompat.setOnApplyWindowInsetsListener(it, null) }
            exactWindowDecorViews.clear()
        }
        if (::images.isInitialized) images.close()
        if (::client.isInitialized) {
            client.dispatcher.cancelAll()
            client.connectionPool.evictAll()
            client.dispatcher.executorService.shutdownNow()
        }
        originalSystemFontScale?.let { original ->
            compose.runOnUiThread { RuntimeEnvironment.setFontScale(original) }
            originalSystemFontScale = null
        }
        assertTrue("No actual transport may be attempted: $blocked", blocked.isEmpty())
    }

    @Test fun feedFourImagesOpensTheClickedDuplicateOccurrenceAndRetainsScroll() =
        exercise("feed", 4, 2, 1f, "feed-four")

    @Test fun replyNineImagesOpensTheClickedDuplicateOccurrenceAndRetainsScroll() =
        exercise("feed_reply", 9, 5, 1f, "reply-nine")

    @Test fun genericTwelveImagesMoreOpensTheTenthDuplicateOccurrenceAndRetainsScroll() =
        exercise("text", 12, 9, 1f, "generic-twelve")

    @Test @Config(qualifiers = "w320dp-h600dp-320dpi")
    fun compactFeedFourHasReachableViewerControlsAndRetainsScroll() =
        exercise("feed", 4, 2, 1f, "compact-feed-four")

    @Test fun largeFontReplyNineHasReachableViewerControlsAndRetainsScroll() =
        exercise("feed_reply", 9, 5, 1.6f, "font1.6-reply-nine")

    @Test @Config(qualifiers = "w700dp-h393dp-land-320dpi")
    fun landscapeGenericTwelveHasReachableViewerControlsAndRetainsScroll() =
        exercise("text", 12, 9, 1f, "landscape-generic-twelve")

    @Test fun failedFeedGridStillOpensViewerAndErrorBackRetainsScroll() =
        exerciseFailure("feed", 4, 1f, "feed-error")

    @Test @Config(qualifiers = "w320dp-h600dp-320dpi")
    fun compactReplyFailureHasOneExplicitRetryAndReachableBack() =
        exerciseFailure("feed_reply", 9, 1f, "compact-reply-error")

    @Test fun largeFontGenericFailureHasOneExplicitRetryAndReachableBack() =
        exerciseFailure("text", 4, 1.6f, "font1.6-generic-error")

    @Test @Config(qualifiers = "w700dp-h393dp-land-320dpi")
    fun landscapeFeedFailureHasOneExplicitRetryAndReachableBack() =
        exerciseFailure("feed", 4, 1f, "landscape-feed-error")

    @Test @Config(qualifiers = "w240dp-h600dp-320dpi")
    fun veryNarrowReplyNineUsesTwoColumnsAndRetainsReadySourceGeometry() =
        exercise("feed_reply", 9, 5, 1f, "very-narrow-reply-nine", expectedColumns = 2)

    @Test @Config(qualifiers = "w240dp-h600dp-320dpi")
    fun veryNarrowReplyFailureKeepsRetryAndBackReachable() =
        exerciseFailure("feed_reply", 9, 1f, "very-narrow-reply-error", expectedColumns = 2)

    @Test @Config(qualifiers = "w320dp-h600dp-320dpi")
    fun compactLargeFontReplyFailureKeepsRetryAndBackReachable() =
        exerciseFailure("feed_reply", 9, 1.6f, "compact-font1.6-reply-error")

    @Test @Config(qualifiers = "w700dp-h393dp-land-320dpi")
    fun landscapeLargeFontFeedFailureKeepsRetryAndBackReachable() =
        exerciseFailure("feed", 4, 1.6f, "landscape-font1.6-feed-error")

    /** Synthetic mapping of a reported window, not a native-device measurement or reproduction.
     * Both raw top values coexist: 140px status/viewPadding, 179px systemGestures; never summed.
     * The display and Activity are exactly 1200x2670px. The w369dp/h822dp integer configuration
     * is a synthetic fixture choice, not an observed device Configuration. Pinned Compose 1.7.6
     * naturally quantizes the independent Dialog using round(configurationDp * resourceDensity).
     * Observe that exact rule without resizing the Dialog or inventing a device-specific branch.
     */
    @Test @Config(qualifiers = "w369dp-h822dp-520dpi")
    fun exactReportedWindowLoadsFailsExplicitlyRetriesAndRetainsReadySource() {
        val viewport = ExactViewport(1200, 2670, 3.25f, statusTopPx = 140, gestureTopPx = 179)
        lateinit var pending: ControlledGalleryImages.Gate
        val urls = render("feed", 4, 1f, viewport = viewport, prepareImages = { loader, urls ->
            pending = loader.next(urls[2])
        })
        val entry = "media-cell-2"
        exposeSource(entry)
        rememberSourcePosition(entry)
        assertExactWindow(dialog = false)
        capture("exact-reported-feed-grid", dialog = false)
        compose.onNodeWithTag(entry).performTouchInput { click(center) }
        compose.onAllNodes(isDialog()).assertCountEquals(1)
        applyExactWindow(requireNotNull(currentDialog().window), "dialog")
        assertGalleryPage(2, 4)
        awaitPhase(2, "加载中")
        assertExactWindow(dialog = true)
        assertExactTargetSafety()
        capture("exact-reported-dialog-loading", dialog = true)
        pending.result.complete(false)
        awaitPhase(2, "加载失败")
        assertExactTargetSafety(retryIndex = 2)
        capture("exact-reported-dialog-error", dialog = true)
        val beforeRetry = images.count(urls[2])
        val retry = images.next(urls[2])
        dialogTag("media-viewer-retry-2").performTouchInput { click(center) }
        awaitPhase(2, "加载中")
        compose.waitUntil(10_000) { images.count(urls[2]) == beforeRetry + 1 }
        assertEquals(beforeRetry + 1, images.count(urls[2]))
        capture("exact-reported-dialog-retry-loading", dialog = true)
        retry.result.complete(true)
        awaitPhase(2, "已加载")
        assertExactTargetSafety()
        capture("exact-reported-dialog-success", dialog = true)
        dialogWindowBack()
        assertSourceRetained(entry)
        assertExactWindow(dialog = false)
        capture("exact-reported-feed-back", dialog = false)
    }

    /** Can be copied to the pre-change revision: only pre-existing cell tags / visible text are used.
     * Baseline and candidate both execute the real entry route and draw the real Dialog window.
     */
    @Test fun baselineCompatibleCapturesActualFeedGridAndDialog() {
        render("feed", 4, 1f, duplicateAt = 2)
        exposeSource("media-cell-2")
        rememberSourcePosition("media-cell-2")
        capture("reference-feed-grid", dialog = false)
        compose.onNodeWithTag("media-cell-2").performTouchInput { click(center) }
        dialogText("3/4").assertIsDisplayed()
        compose.waitUntil(10_000) { images.completed.contains(imageUrl(0)) }
        capture("reference-feed-dialog", dialog = true)
        dialogText("下一张").performTouchInput { click(center) }
        dialogText("4/4").assertIsDisplayed()
        capture("reference-feed-dialog-next", dialog = true)
        dialogWindowBack()
        assertSourceRetained("media-cell-2")
        capture("reference-feed-back", dialog = false)
    }

    /** Failure comparison deliberately makes no assertion about new failure labels or new tags. */
    @Test fun baselineCompatibleCapturesActualFailedGenericDialog() {
        val urls = render("text", 4, 1f, failedIndex = 2)
        exposeSource("media-cell-2")
        rememberSourcePosition("media-cell-2")
        capture("reference-generic-error-grid", dialog = false)
        compose.onNodeWithTag("media-cell-2").performTouchInput { click(center) }
        dialogText("3/4").assertIsDisplayed()
        compose.waitUntil(10_000) { images.completed.contains(urls[2]) }
        capture("reference-generic-error-dialog", dialog = true)
        dialogWindowBack()
        assertSourceRetained("media-cell-2")
    }

    private fun exercise(kind: String, count: Int, selected: Int, scale: Float, name: String, expectedColumns: Int? = null) {
        val urls = render(kind, count, scale, duplicateAt = selected)
        assertEquals("The fixture must contain exactly equal URLs at separate occurrences", urls[0], urls[selected])
        val entry = if (selected >= 9) "media-more" else "media-cell-$selected"
        exposeSource(entry)
        compose.onNodeWithTag(entry).assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp)
        compose.onNodeWithTag("media-cell-9").assertDoesNotExist()
        expectedColumns?.let { assertAdaptiveReplyGrid(it) }
        rememberSourcePosition(entry)
        capture("$name-before", dialog = false)
        compose.onNodeWithTag(entry).performTouchInput { click(center) }
        assertGalleryPage(selected, count)
        awaitPhase(selected, "已加载")
        assertViewerTargets()
        capture("$name-dialog", dialog = true)
        dialogTag("media-previous").performTouchInput { click(center) }
        assertGalleryPage(selected - 1, count)
        dialogTag("media-next").performTouchInput { click(center) }
        assertGalleryPage(selected, count)
        capture("$name-dialog-returned", dialog = true)
        dialogWindowBack()
        assertSourceRetained(entry)
        capture("$name-back", dialog = false)
        // Reopening also proves the card and its image target remain usable, not just the list state.
        compose.onNodeWithTag(entry).performTouchInput { click(center) }
        assertGalleryPage(selected, count)
        dialogTag("media-back").performTouchInput { click(center) }
        assertSourceRetained(entry)
    }

    private fun exerciseFailure(kind: String, count: Int, scale: Float, name: String, expectedColumns: Int? = null) {
        val selected = 2
        val urls = render(kind, count, scale, failedIndex = selected)
        val url = urls[selected]
        val entry = "media-cell-$selected"
        exposeSource(entry)
        awaitPhase(selected, "加载失败", surface = "grid")
        compose.onNodeWithTag(entry).assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp)
        compose.onNodeWithTag("media-grid-retry-$selected").assertDoesNotExist()
        expectedColumns?.let { assertAdaptiveReplyGrid(it) }
        rememberSourcePosition(entry)
        capture("$name-grid", dialog = false)
        compose.onNodeWithTag(entry).performTouchInput { click(center) }
        assertGalleryPage(selected, count)
        awaitPhase(selected, "加载失败")
        assertViewerTargets(retryIndex = selected)
        // Keep a real Dialog capture even if the new closed failure reason's geometry regresses.
        capture("$name-dialog", dialog = true)
        assertFailureReason(selected)
        val beforeBack = images.count(url)
        dialogWindowBack()
        assertSourceRetained(entry)
        assertEquals("Back must not retry a failed original image", beforeBack, images.count(url))
        capture("$name-error-back", dialog = false)
        // A fresh opening gets a fresh request; only the explicit viewer retry retries that opening.
        images.next(url).result.complete(false)
        compose.onNodeWithTag(entry).performTouchInput { click(center) }
        assertGalleryPage(selected, count)
        awaitPhase(selected, "加载失败")
        assertFailureReason(selected)
        val beforeRetry = images.count(url)
        images.next(url).result.complete(true)
        dialogTag("media-viewer-retry-$selected").performTouchInput { click(center) }
        awaitPhase(selected, "已加载")
        assertEquals("Explicit retry must start exactly one new original request", beforeRetry + 1, images.count(url))
        dialogTag("media-viewer-retry-$selected").assertDoesNotExist()
        dialogTag("media-viewer-failure-reason-$selected").assertDoesNotExist()
        capture("$name-retried", dialog = true)
        dialogTag("media-back").performTouchInput { click(center) }
        assertSourceRetained(entry)
    }

    private fun assertGalleryPage(index: Int, count: Int) {
        compose.onAllNodes(isDialog()).assertCountEquals(1)
        dialogTag("media-gallery").assertIsDisplayed()
        dialogTag("media-page-count").assertTextEquals("${index + 1}/$count")
        assertEquals("Gallery paging must not load home or navigate to a feed detail", listOf("home"), requests.toList())
    }

    private fun awaitPhase(index: Int, phase: String, surface: String = "viewer") {
        compose.waitUntil(10_000) {
            compose.onAllNodesWithTag("media-$surface-image-$index", useUnmergedTree = true)
                .fetchSemanticsNodes().any { it.config.contains(SemanticsProperties.StateDescription) &&
                    it.config[SemanticsProperties.StateDescription] == "图片 ${index + 1}，$phase" }
        }
    }

    private fun dialogTag(tag: String) = compose.onNode(hasTestTag(tag) and hasAnyAncestor(isDialog()))
    private fun dialogText(text: String) = compose.onNode(hasText(text) and hasAnyAncestor(isDialog()))

    /** ControlledGalleryImages returns an IOException without pipeline-phase evidence. The UI
     * must show the closed ambiguous reason, not guess network/decode or expose exception text.
     */
    private fun assertFailureReason(index: Int) {
        val label = "图片读取或解码失败"
        val reason = dialogTag("media-viewer-failure-reason-$index").assertTextEquals(label).assertIsDisplayed()
        val layouts = mutableListOf<TextLayoutResult>()
        reason.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { action -> assertTrue(action(layouts)) }
        assertEquals(1, layouts.size)
        val layout = layouts.single()
        assertCompleteViewerParagraph(label, reason, layout)
        assertEquals(fontScale, layout.layoutInput.density.fontScale, .001f)
        assertEquals(density, layout.layoutInput.density.density, .001f)
        val bounds = reason.fetchSemanticsNode().boundsInWindow
        val uncut = reason.getUnclippedBoundsInRoot()
        assertEquals("Failure reason may not be horizontally clipped", (uncut.right - uncut.left).value * density, bounds.width, 1f)
        assertEquals("Failure reason may not be vertically clipped", (uncut.bottom - uncut.top).value * density, bounds.height, 1f)
        val page = dialogTag("media-viewer-image-$index").fetchSemanticsNode().boundsInWindow
        val window = viewBoundsInWindow(checkNotNull(currentDialog().window).decorView)
        assertTrue("Failure reason must fit the actual image page", inside(bounds, page))
        assertTrue("Failure reason must fit the actual Dialog", inside(bounds, window))
        assertFalse("Failure reason must not cover the retry target", bounds.overlaps(
            dialogTag("media-viewer-retry-$index").fetchSemanticsNode().boundsInWindow))
    }

    private fun assertViewerTargets(retryIndex: Int? = null) {
        measureViewerText(retryRequired = retryIndex != null)
        val tags = listOf("media-back", "media-previous", "media-next") + listOfNotNull(retryIndex?.let { "media-viewer-retry-$it" })
        val window = compose.onNode(isDialog()).fetchSemanticsNode().boundsInRoot
        val bounds = tags.associateWith { tag ->
            val target = dialogTag(tag).assertIsDisplayed().assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp)
            val clipped = target.fetchSemanticsNode().boundsInRoot
            val uncut = target.getUnclippedBoundsInRoot()
            assertEquals("$tag may not be horizontally clipped", (uncut.right - uncut.left).value * density, clipped.width, 1f)
            assertEquals("$tag may not be vertically clipped", (uncut.bottom - uncut.top).value * density, clipped.height, 1f)
            assertTrue("$tag must be inside the actual Dialog", inside(clipped, window))
            clipped
        }
        bounds.entries.forEachIndexed { index, first -> bounds.entries.drop(index + 1).forEach { second ->
            assertFalse("Targets must not overlap: ${first.key}, ${second.key}", first.value.overlaps(second.value))
        } }
    }

    /** Measure production Text engine inputs inside the real Dialog, never infer scale from an
     * outer composition-local request or from identical minimum-size button rectangles.
     */
    private fun measureViewerText(retryRequired: Boolean = false): JsonObject {
        val labels = listOf("返回", "上一张", "下一张", "重试本图")
        val dialogResourceScale = currentDialog().context.resources.configuration.fontScale
        assertEquals("The real Dialog resource environment must carry requested font scale", fontScale, dialogResourceScale, .001f)
        return buildJsonObject {
            put("requestedFontScale", fontScale)
            put("actualDialogResourceFontScale", dialogResourceScale)
            put("textLayouts", buildJsonObject {
                labels.forEach { label ->
                    val matcher = hasText(label) and hasAnyAncestor(isDialog())
                    val matching = compose.onAllNodes(matcher, useUnmergedTree = true).fetchSemanticsNodes()
                    if (label == "重试本图" && matching.isEmpty() && !retryRequired) return@forEach
                    assertEquals("Exactly one actual viewer Text for $label", 1, matching.size)
                    val text = compose.onNode(matcher, useUnmergedTree = true)
                    val layouts = mutableListOf<TextLayoutResult>()
                    text.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { action -> assertTrue(action(layouts)) }
                    assertEquals("Exactly one production text layout for $label", 1, layouts.size)
                    val layout = layouts.single()
                    try { assertVisibleViewerText(label, text, layout) }
                    catch (failure: AssertionError) {
                        captureViewerTextOverflowProbe(label, text, layout)
                        throw failure
                    }
                    val actual = layout.layoutInput.density
                    assertEquals("$label actual TextLayoutResult fontScale must match the requested scenario", fontScale, actual.fontScale, .001f)
                    assertEquals("$label actual TextLayoutResult pixel density", density, actual.density, .001f)
                    val firstGlyph = layout.getBoundingBox(0)
                    assertTrue("$label must have measured glyph geometry", firstGlyph.width > 0f && firstGlyph.height > 0f)
                    val fontSize = layout.layoutInput.style.fontSize
                    assertTrue("$label production typography uses a scalable SP font", fontSize.isSp)
                    val actualFontSizePx = with(actual) { fontSize.toPx() }
                    put(label, buildJsonObject {
                        put("requestedFontScale", fontScale)
                        put("actualFontScale", actual.fontScale); put("actualDensity", actual.density)
                        put("actualFontSize", layout.layoutInput.style.fontSize.toString())
                        put("actualLineHeight", layout.layoutInput.style.lineHeight.toString())
                        put("actualLayoutWidthPx", layout.size.width); put("actualLayoutHeightPx", layout.size.height)
                        put("semanticsParagraphWidthPx", layout.multiParagraph.width)
                        put("paragraphMaxIntrinsicWidthPx", layout.multiParagraph.intrinsics.maxIntrinsicWidth)
                        put("actualFirstGlyphBoundsPx", boundsJson(firstGlyph))
                        put("actualFontSizePx", actualFontSizePx)
                        put("lineCount", layout.lineCount)
                        put("actualGlyphBoundsPx", buildJsonArray {
                            for (offset in layout.layoutInput.text.indices) add(boundsJson(layout.getBoundingBox(offset)))
                        })
                        put("actualLineMetrics", buildJsonArray {
                            for (line in 0 until layout.lineCount) add(buildJsonObject {
                                put("line", line); put("leftPx", layout.getLineLeft(line)); put("rightPx", layout.getLineRight(line))
                                put("topPx", layout.getLineTop(line)); put("bottomPx", layout.getLineBottom(line))
                                put("startCharacter", layout.getLineStart(line)); put("endCharacter", layout.getLineEnd(line))
                            })
                        })
                        put("didOverflowWidth", layout.didOverflowWidth); put("didOverflowHeight", layout.didOverflowHeight)
                        put("actualTextBoundsInWindowPx", boundsJson(text.fetchSemanticsNode().boundsInWindow))
                    })
                }
            })
        }
    }

    /** String Text semantics in pinned Compose 1.7.6 rebuilds a paragraph at parent maxWidth
     * while retaining the real narrow layoutSize. v8 recorded Back text 92px wide, full glyphs
     * through 90.32143px, but a reconstructed 1108px paragraph. Its generic width flag is not a
     * clipping witness. Require every character/line and the actual Text/Button/window to fit.
     */
    private fun assertVisibleViewerText(label: String, text: SemanticsNodeInteraction, layout: TextLayoutResult) {
        assertCompleteViewerParagraph(label, text, layout)
        val button = compose.onNode(hasText(label) and hasAnyAncestor(isDialog()))
            .assertIsDisplayed().assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp)
        val textBounds = text.fetchSemanticsNode().boundsInWindow
        val buttonBounds = button.fetchSemanticsNode().boundsInWindow
        val windowBounds = viewBoundsInWindow(checkNotNull(currentDialog().window).decorView)
        for ((name, node) in listOf("Text" to text, "Button" to button)) {
            val clipped = node.fetchSemanticsNode().boundsInWindow
            val uncut = node.getUnclippedBoundsInRoot()
            // Same 1px dp conversion tolerance as the existing button-clipping check; no inset,
            // physical-window or glyph/line threshold is relaxed by this comparison.
            assertEquals("$label $name may not be horizontally clipped", (uncut.right - uncut.left).value * density, clipped.width, 1f)
            assertEquals("$label $name may not be vertically clipped", (uncut.bottom - uncut.top).value * density, clipped.height, 1f)
        }
        fun contains(outer: Rect, inner: Rect) = inner.left >= outer.left && inner.top >= outer.top &&
            inner.right <= outer.right && inner.bottom <= outer.bottom
        assertTrue("$label complete Text must fit its actual Button", contains(buttonBounds, textBounds))
        assertTrue("$label complete Button must fit the real Dialog", contains(windowBounds, buttonBounds))
        exactViewport?.let { exact ->
            assertTrue("$label complete Text and Button must also fit the reported physical host", contains(
                Rect(0f, 0f, exact.widthPx.toFloat(), (exact.heightPx - exact.bottomPx).toFloat()), buttonBounds))
        }
    }

    /** Shared unchanged character/line visibility checks for both buttons and non-action reasons. */
    private fun assertCompleteViewerParagraph(label: String, text: SemanticsNodeInteraction, layout: TextLayoutResult) {
        text.assertIsDisplayed()
        val input = layout.layoutInput
        assertEquals("The selected actual Text must be the expected label", label, input.text.text)
        assertFalse("$label actual viewer text may not overflow vertically", layout.didOverflowHeight)
        assertFalse("$label must not exceed maxLines", layout.multiParagraph.didExceedMaxLines)
        assertTrue("$label needs an actual laid-out line", layout.lineCount > 0)
        assertEquals("$label must include its first character", 0, layout.getLineStart(0))
        assertEquals("$label must include all characters", input.text.length, layout.getLineEnd(layout.lineCount - 1))
        for (line in 0 until layout.lineCount) {
            assertFalse("$label line $line must not be ellipsized", layout.isLineEllipsized(line))
            assertTrue("$label line $line must fit the actual layout width", layout.getLineLeft(line) >= 0f &&
                layout.getLineRight(line) <= layout.size.width)
            assertTrue("$label line $line must fit the actual layout height", layout.getLineTop(line) >= 0f &&
                layout.getLineBottom(line) <= layout.size.height)
            if (line > 0) assertEquals("$label lines must cover text without gaps",
                layout.getLineEnd(line - 1), layout.getLineStart(line))
        }
        for (offset in input.text.indices) {
            val glyph = layout.getBoundingBox(offset)
            assertTrue("$label glyph $offset must be visible within the actual layout", glyph.width > 0f && glyph.height > 0f &&
                glyph.left >= 0f && glyph.top >= 0f && glyph.right <= layout.size.width && glyph.bottom <= layout.size.height)
        }
    }

    /** Evidence must survive a failed text-visibility assertion. Draw the real Dialog, and retain
     * both String Text semantics' paragraph constraints and the actual narrow text/layout bounds.
     */
    private fun captureViewerTextOverflowProbe(label: String, text: SemanticsNodeInteraction, layout: TextLayoutResult) {
        val directory = File("build/verification/gallery").apply { mkdirs() }
        val input = layout.layoutInput
        val button = compose.onNode(hasText(label) and hasAnyAncestor(isDialog()))
        val stem = "root-overflow-probe-${fontScale}-${label}"
        val probe = buildJsonObject {
            put("label", label); put("requestedFontScale", fontScale)
            put("actualFontScale", input.density.fontScale); put("actualDensity", input.density.density)
            put("inputText", input.text.text); put("fontSize", input.style.fontSize.toString())
            put("lineHeight", input.style.lineHeight.toString()); put("inputConstraints", input.constraints.toString())
            put("actualLayoutWidthPx", layout.size.width); put("actualLayoutHeightPx", layout.size.height)
            put("paragraphWidthPx", layout.multiParagraph.width); put("paragraphHeightPx", layout.multiParagraph.height)
            put("paragraphMaxIntrinsicWidthPx", layout.multiParagraph.intrinsics.maxIntrinsicWidth)
            put("didOverflowWidth", layout.didOverflowWidth); put("didOverflowHeight", layout.didOverflowHeight)
            put("didExceedMaxLines", layout.multiParagraph.didExceedMaxLines)
            put("maxLines", input.maxLines); put("softWrap", input.softWrap); put("overflow", input.overflow.toString())
            put("actualTextBoundsInWindowPx", boundsJson(text.fetchSemanticsNode().boundsInWindow))
            put("actualButtonBoundsInWindowPx", boundsJson(button.fetchSemanticsNode().boundsInWindow))
            put("actualDialogBoundsInWindowPx", boundsJson(compose.onNode(isDialog()).fetchSemanticsNode().boundsInWindow))
            put("lines", buildJsonArray {
                for (line in 0 until layout.lineCount) add(buildJsonObject {
                    put("line", line); put("start", layout.getLineStart(line)); put("end", layout.getLineEnd(line))
                    put("leftPx", layout.getLineLeft(line)); put("rightPx", layout.getLineRight(line))
                    put("topPx", layout.getLineTop(line)); put("bottomPx", layout.getLineBottom(line))
                    put("ellipsized", layout.isLineEllipsized(line))
                })
            })
            put("glyphs", buildJsonArray {
                for (offset in input.text.indices) add(buildJsonObject {
                    put("offset", offset); put("boundsPx", boundsJson(layout.getBoundingBox(offset)))
                })
            })
            exactViewport?.let { put("exactViewport", exactViewportMetrics(dialog = true)) }
        }
        File(directory, "$stem.metrics.json").writeText(Json { prettyPrint = true }.encodeToString(JsonObject.serializer(), probe))
        val bitmap = compose.runOnUiThread {
            val decor = checkNotNull(currentDialog().window).decorView
            Bitmap.createBitmap(decor.width, decor.height, Bitmap.Config.ARGB_8888).also { decor.draw(Canvas(it)) }
        }
        try { File(directory, "$stem.png").outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) } }
        finally { bitmap.recycle() }
    }

    private fun exposeSource(tag: String) {
        compose.onNodeWithTag("content-list").performScrollToNode(hasTestTag(tag))
        // Real background HTML parsing must have replaced the placeholder before positioning the
        // target. Otherwise an 8px body-height change can pass index/offset-only restoration checks.
        awaitSourceBodyReady()
        compose.onNodeWithTag(tag).performScrollTo()
        fun exposed(): Boolean {
            val target = compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot
            val viewport = compose.onNodeWithTag("content-list").fetchSemanticsNode().boundsInRoot
            val dock = compose.onAllNodesWithTag("floating-navigation").fetchSemanticsNodes()
            return target.width > 0 && target.height > 0 && inside(target, viewport) && dock.none { it.boundsInRoot.overlaps(target) }
        }
        repeat(8) {
            if (!exposed()) {
                compose.onNodeWithTag("content-list").performTouchInput {
                    swipe(androidx.compose.ui.geometry.Offset(centerX, height * .7f),
                        androidx.compose.ui.geometry.Offset(centerX, height * .5f), durationMillis = 500)
                }
                compose.waitForIdle()
            }
        }
        // Use a genuinely non-zero offset, not just a restored first item, in every restoration test.
        if (homeState.firstVisibleItemScrollOffset == 0) {
            compose.runOnIdle { runBlocking { homeState.scrollToItem(homeState.firstVisibleItemIndex, 7) } }
        }
        assertTrue("Entry must be fully reachable outside the floating dock: $tag", exposed())
        compose.onNodeWithTag(tag).assertIsDisplayed()
    }

    private fun rememberSourcePosition(tag: String) {
        awaitSourceBodyReady()
        sourceEntryTag = tag
        sourcePosition = homeState.firstVisibleItemIndex to homeState.firstVisibleItemScrollOffset
        sourceGeometry = measureSourceGeometry(tag)
        assertTrue("Source list index must be positive: $sourcePosition", sourcePosition!!.first > 0)
        assertTrue("Source list offset must be positive: $sourcePosition", sourcePosition!!.second > 0)
        assertEquals(listOf("home"), requests.toList())
    }

    private fun assertSourceRetained(tag: String) {
        compose.waitForIdle()
        compose.onAllNodes(isDialog()).assertCountEquals(0)
        compose.onNodeWithTag(tag).assertIsDisplayed()
        // No await/re-scroll here: returning must expose the already-ready source immediately.
        assertSourceBodyReady()
        assertEquals("Back must return to the same source entry", sourceEntryTag, tag)
        assertEquals("Source entry, body and viewport bounds must all remain exactly unchanged",
            sourceGeometry, measureSourceGeometry(tag))
        assertEquals("The original home list index is retained", sourcePosition!!.first, homeState.firstVisibleItemIndex)
        assertEquals("The original home list pixel offset is retained", sourcePosition!!.second, homeState.firstVisibleItemScrollOffset)
        assertEquals("No home refetch, detail navigation or other service call", listOf("home"), requests.toList())
    }

    private fun awaitSourceBodyReady() {
        compose.waitUntil(10_000) {
            compose.onAllNodes(hasText(sourceBodyText) and hasAnyAncestor(hasTestTag("post-preview-block")),
                useUnmergedTree = true).fetchSemanticsNodes().size == 1 &&
                compose.onAllNodesWithText("正在排版…", useUnmergedTree = true).fetchSemanticsNodes().isEmpty()
        }
        compose.waitForIdle()
        assertSourceBodyReady()
    }

    private fun assertSourceBodyReady() {
        compose.onNode(hasText(sourceBodyText) and hasAnyAncestor(hasTestTag("post-preview-block")),
            useUnmergedTree = true).assertExists()
        compose.onNodeWithText("正在排版…", useUnmergedTree = true).assertDoesNotExist()
    }

    private fun sourceBody() = compose.onNodeWithTag("post-preview-block", useUnmergedTree = true)
    private fun sourceText() = compose.onNode(hasText(sourceBodyText) and hasAnyAncestor(hasTestTag("post-preview-block")),
        useUnmergedTree = true)

    private fun unclippedPx(node: SemanticsNodeInteraction): Rect = node.getUnclippedBoundsInRoot().let {
        Rect(it.left.value * density, it.top.value * density, it.right.value * density, it.bottom.value * density)
    }

    private fun measureSourceGeometry(tag: String): Map<String, Rect> = linkedMapOf(
        "entryClipped" to compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot,
        "entryUnclipped" to unclippedPx(compose.onNodeWithTag(tag)),
        "bodyClipped" to sourceBody().fetchSemanticsNode().boundsInRoot,
        "bodyUnclipped" to unclippedPx(sourceBody()),
        "bodyTextClipped" to sourceText().fetchSemanticsNode().boundsInRoot,
        "bodyTextUnclipped" to unclippedPx(sourceText()),
        "viewport" to compose.onNodeWithTag("content-list").fetchSemanticsNode().boundsInRoot,
    )

    /** 240dp real home minus list margins, reply avatar and row padding leaves <154dp for images.
     * Check rendered cell positions, not the layout helper, so silently retaining three columns fails.
     */
    private fun assertAdaptiveReplyGrid(expectedColumns: Int) {
        assertEquals("feed_reply", sourceKind)
        assertEquals(9, sourceCount)
        val body = unclippedPx(sourceBody())
        assertTrue("The real reply image container must exercise the sub-154dp branch", body.width / density < 154f)
        val cells = (0 until 9).map { index ->
            val cell = compose.onNodeWithTag("media-cell-$index")
            cell.assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp)
            unclippedPx(cell).also { bounds ->
                assertTrue("Cell $index may not overflow the actual reply width", bounds.left >= body.left - 1f && bounds.right <= body.right + 1f)
            }
        }
        assertEquals("The first row must contain the adaptive column count", expectedColumns,
            cells.count { it.top == cells[0].top })
        assertTrue("The next occurrence must start the next row", cells[expectedColumns].top > cells[0].top)
        assertEquals(cells[0].left, cells[expectedColumns].left, 0f)
        cells.forEachIndexed { index, first -> cells.drop(index + 1).forEach { second ->
            assertFalse("Distinct image targets may not overlap", first.overlaps(second))
        } }
    }

    /** Dispatch Back to the shown Dialog's window, not the Activity dispatcher behind it. */
    private fun dialogWindowBack() {
        compose.onAllNodes(isDialog()).assertCountEquals(1)
        compose.runOnUiThread {
            val dialog = currentDialog()
            check(dialog is ComponentDialog)
            check(dialog.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK)))
            check(dialog.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK)))
        }
        compose.waitForIdle()
    }

    private fun currentDialog(): android.app.Dialog {
        val shown = ShadowDialog.getShownDialogs().filter { it.isShowing }.distinct()
        check(shown.size == 1) { "Expected one shown production gallery Dialog, found ${shown.size}" }
        val current = requireNotNull(ShadowDialog.getLatestDialog())
        check(current === shown.single())
        val window = requireNotNull(current.window)
        check(window !== compose.activity.window && window.decorView !== compose.activity.window.decorView)
        check(window.decorView.isAttachedToWindow && window.callback === current)
        check(window.attributes.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE == 0)
        return current
    }

    private fun render(kind: String, count: Int, scale: Float, duplicateAt: Int? = null, failedIndex: Int? = null,
        viewport: ExactViewport? = null, prepareImages: ((ControlledGalleryImages, List<String>) -> Unit)? = null): List<String> {
        sourceKind = kind; sourceCount = count; fontScale = scale; exactViewport = viewport
        // A new Dialog installs platform locals from its own resources. An outer LocalDensity
        // override alone does not prove viewer scaling. Configure the real test resource system
        // before root composition and before any Dialog exists, using Robolectric's public API.
        compose.runOnUiThread {
            check(originalSystemFontScale == null)
            originalSystemFontScale = RuntimeEnvironment.getFontScale()
            RuntimeEnvironment.setFontScale(scale)
            assertEquals("Activity resource fontScale must be configured before composition", scale,
                compose.activity.resources.configuration.fontScale, .001f)
            assertEquals("Application resource fontScale must reach subsequently created Dialogs", scale,
                compose.activity.application.resources.configuration.fontScale, .001f)
        }
        viewport?.let { exact -> compose.runOnUiThread {
            shadowOf(ShadowDisplay.getDefaultDisplay()).apply {
                setWidth(exact.widthPx); setHeight(exact.heightPx)
                setRealWidth(exact.widthPx); setRealHeight(exact.heightPx)
            }
            WindowCompat.setDecorFitsSystemWindows(compose.activity.window, false)
            compose.activity.window.setLayout(exact.widthPx, exact.heightPx)
        } }
        val urls = List(count) { imageUrl(if (it == duplicateAt) 0 else it) }
        images = ControlledGalleryImages(compose.activity)
        failedIndex?.let { index ->
            images.next(urls[index] + ".s.jpg").result.complete(false)
            images.next(urls[index]).result.complete(false)
        }
        prepareImages?.invoke(images, urls)
        val container = AppContainer(compose.activity.application).also { it.nativeUpdates.enabled = false }
        AppContainer::class.java.getDeclaredField("local").apply { isAccessible = true }
            .set(container, LocalRepository(MemoryLocal()))
        val store = AccountStore(object : CredentialPersistence {
            override fun read(): ByteArray? = null
            override fun write(bytes: ByteArray) {}
            override fun reset() {}
        })
        client = OkHttpClient.Builder().addInterceptor {
            blocked += it.request().url.toString(); throw IOException("Offline root gallery fixture")
        }.build()
        val login = HttpLoginTransport(client)
        val social = SocialRepository(store) { snapshot ->
            CoolapkHttpTransport(client = client, requestSession = { store.requestSessionFor(snapshot) })
        }
        val ctor = AccountRuntime::class.java.getDeclaredConstructor(AccountStore::class.java,
            HttpLoginTransport::class.java, LoginCoordinator::class.java, HttpCoolapkRepository::class.java,
            SocialRepository::class.java).apply { isAccessible = true }
        val accounts = ctor.newInstance(store, login, LoginCoordinator(store, login, "synthetic"),
            HttpCoolapkRepository(client = client), social)
        val fixture = ContentItem("9100", kind, title = "本地图库验证", message = sourceBodyText,
            userName = "离线图像作者", imageUrls = urls)
        val rows = (0 until 16).map { ContentItem("before-$it", "text", title = "离线首页内容 ${it + 1}") } +
            fixture + (0 until 12).map { ContentItem("after-$it", "text", title = "后续首页内容 ${it + 1}") }
        val repo = Proxy.newProxyInstance(CoolapkRepository::class.java.classLoader,
            arrayOf(CoolapkRepository::class.java)) { _, method, _ ->
            when (method.name) {
                "home" -> { requests += "home"; ApiResult.Success(Page(rows, null)) }
                "toString" -> "OfflineGalleryRootRepository"
                else -> throw AssertionError("Unexpected repository call ${method.name}")
            }
        } as CoolapkRepository
        compose.setContent {
            val accountState by store.state.collectAsState()
            val model = remember(accountState.generation) { BrowseViewModel(repo) }
            val platformDensity = LocalDensity.current
            SideEffect { density = platformDensity.density; actualRootFontScale = platformDensity.fontScale }
            CompositionLocalProvider(LocalMediaImageLoader provides images.loader,
                LocalReadingStyles provides readingStyles(ReadingPreset.COMFORTABLE, FontChoice.NOTO_SANS)) {
                MaterialTheme(colorScheme = flutterReferenceColorScheme(), typography = appTypography(FontChoice.NOTO_SANS)) {
                    if (viewport != null) {
                        val effective = LocalDensity.current
                        val status = WindowInsets.statusBars.getTop(effective)
                        val gestures = WindowInsets.systemGestures.getTop(effective)
                        val bottom = WindowInsets.safeDrawing.getBottom(effective)
                        SideEffect { rootComposeInsets = Triple(status, gestures, bottom) }
                    }
                    key(accountState.generation) {
                        NativeApp(model, container, AppSettings(floatingNavigation = true, dynamicColor = false),
                            accounts, accountState, browseListStates = mapOf("home" to homeState))
                    }
                }
            }
        }
        viewport?.let { applyExactWindow(compose.activity.window, "activity") }
        compose.waitUntil(10_000) { requests.contains("home") }
        return urls
    }

    /** Supply environment pixels/insets without changing the production window's decor-fit policy.
     * Activity setup matches MainActivity.enableEdgeToEdge(); the production Dialog keeps its
     * declared decorFitsSystemWindows=true. API35 may enforce a different effective policy for
     * targetSDK35+; observe both without changing either. The observer invokes the default inset
     * handler, records its result and never inserts fixture padding or forces content dimensions.
     */
    private fun applyExactWindow(window: Window, label: String) {
        val exact = checkNotNull(exactViewport)
        compose.runOnUiThread {
            val policyBefore = observeWindowPolicy(window, label)
            assertProductionWindowPolicy(label, policyBefore)
            windowPoliciesBefore[label] = policyBefore
            if (label == "activity") window.setLayout(exact.widthPx, exact.heightPx)
            val decor = window.decorView
            exactWindowDecorViews += decor
            ViewCompat.setOnApplyWindowInsetsListener(decor) { view, dispatched ->
                injectedInsetObservations[label] = insetTops(dispatched)
                // Calls View.onApplyWindowInsets directly, not this listener recursively.
                ViewCompat.onApplyWindowInsets(view, dispatched).also {
                    decorAppliedInsetObservations[label] = insetTops(it)
                }
            }
            // Only the host Activity takes the reported physical size. Never resize/measure/layout
            // the Dialog: its pinned production DialogLayout owns its natural quantized dimensions.
            if (label == "activity") {
                decor.measure(View.MeasureSpec.makeMeasureSpec(exact.widthPx, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(exact.heightPx, View.MeasureSpec.EXACTLY))
                decor.layout(0, 0, exact.widthPx, exact.heightPx)
            }
            ViewCompat.dispatchApplyWindowInsets(decor, WindowInsetsCompat.Builder()
                .setInsets(WindowInsetsCompat.Type.statusBars(), Insets.of(0, exact.statusTopPx, 0, 0))
                .setInsets(WindowInsetsCompat.Type.navigationBars(), Insets.of(0, 0, 0, exact.bottomPx))
                .setInsets(WindowInsetsCompat.Type.systemGestures(), Insets.of(0, exact.gestureTopPx, 0, 0))
                .setInsets(WindowInsetsCompat.Type.mandatorySystemGestures(), Insets.NONE)
                .setInsets(WindowInsetsCompat.Type.tappableElement(), Insets.NONE)
                .setInsets(WindowInsetsCompat.Type.ime(), Insets.NONE)
                .setVisible(WindowInsetsCompat.Type.statusBars(), true)
                .setVisible(WindowInsetsCompat.Type.navigationBars(), exact.bottomPx > 0)
                .setVisible(WindowInsetsCompat.Type.ime(), false).build())
            val policyAfter = observeWindowPolicy(window, label)
            windowPoliciesAfter[label] = policyAfter
            assertEquals("Fixture dimensions and insets must not change the declared or effective window policy",
                policyBefore, policyAfter)
        }
        compose.waitForIdle()
    }

    // API 35's runtime method is hidden from the SDK stub; reflection only observes the real flag.
    private fun decorFits(window: Window): Boolean =
        Window::class.java.getMethod("decorFitsSystemWindows").invoke(window) as Boolean

    private fun privateField(instance: Any, name: String): Any? {
        var type: Class<*>? = instance.javaClass
        while (type != null) {
            val owner = type
            val field = runCatching { owner.getDeclaredField(name) }.getOrNull()
            if (field != null) { field.isAccessible = true; return field.get(instance) }
            type = owner.superclass
        }
        error("Missing runtime observation field $name on ${instance.javaClass.name}")
    }

    private fun observeWindowPolicy(window: Window, label: String): WindowPolicyObservation {
        val declared = if (label == "dialog") {
            val dialog = currentDialog()
            check(dialog.window === window)
            (privateField(dialog, "properties") as androidx.compose.ui.window.DialogProperties).decorFitsSystemWindows
        } else null
        return WindowPolicyObservation(declared, decorFits(window),
            privateField(window, "mEdgeToEdgeEnforced") as Boolean,
            window.context.applicationInfo.targetSdkVersion)
    }

    private fun assertProductionWindowPolicy(label: String, policy: WindowPolicyObservation) {
        if (label == "dialog") {
            assertEquals("Production DialogProperties retains its declared default", true, policy.declaredDialogDecorFits)
            if (policy.declaredDialogDecorFits != policy.effectiveDecorFits) {
                assertTrue("A declared/effective difference must be explained by actual platform enforcement",
                    policy.edgeToEdgeEnforced)
            }
        } else assertFalse("Activity retains the real edge-to-edge configuration", policy.effectiveDecorFits)
    }

    private fun insetTops(insets: WindowInsetsCompat) = Triple(
        insets.getInsets(WindowInsetsCompat.Type.statusBars()).top,
        insets.getInsets(WindowInsetsCompat.Type.systemGestures()).top,
        insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom)

    private fun viewBoundsInWindow(view: View): Rect {
        val location = IntArray(2).also(view::getLocationInWindow)
        return Rect(location[0].toFloat(), location[1].toFloat(),
            (location[0] + view.width).toFloat(), (location[1] + view.height).toFloat())
    }

    private fun assertExactWindow(dialog: Boolean) {
        val exact = checkNotNull(exactViewport)
        val label = if (dialog) "dialog" else "activity"
        val window = if (dialog) requireNotNull(currentDialog().window) else compose.activity.window
        val decor = window.decorView
        val content = window.findViewById<View>(android.R.id.content)
        val root = if (dialog) compose.onNode(isDialog()) else compose.onRoot()
        val physical = root.fetchSemanticsNode().boundsInWindow
        val logical = root.getUnclippedBoundsInRoot()
        val currentPolicy = observeWindowPolicy(window, label)
        assertProductionWindowPolicy(label, currentPolicy)
        assertEquals("$label must retain the policy observed before fixture insets and dimensions",
            checkNotNull(windowPoliciesBefore[label]), currentPolicy)
        assertEquals("$label must retain its post-injection policy", windowPoliciesAfter[label], currentPolicy)
        val predicted = if (dialog) predictedProductionDialogSize(decor) else exact.widthPx to exact.heightPx
        assertEquals("$label actual window width must match its exact environment/production rule", predicted.first, decor.width)
        assertEquals("$label actual window height must match its exact environment/production rule, with no clamp tolerance", predicted.second, decor.height)
        val realDisplay = android.graphics.Point().also { ShadowDisplay.getDefaultDisplay().getRealSize(it) }
        assertEquals("The real synthetic display remains the reported physical width", exact.widthPx, realDisplay.x)
        assertEquals("The real synthetic display remains the reported physical height", exact.heightPx, realDisplay.y)
        val decorBounds = viewBoundsInWindow(decor)
        assertEquals("Safety thresholds use the real window origin, not a guessed inset offset", 0f, decorBounds.left, 0f)
        assertEquals("Safety thresholds use the real window origin, not a guessed inset offset", 0f, decorBounds.top, 0f)
        assertEquals("$label effective density", exact.density, density, .001f)
        assertEquals("$label Android density", exact.density, decor.resources.displayMetrics.density, .001f)
        assertEquals("$label window logical width follows its exact measured pixels", predicted.first / exact.density, decor.width / density, .001f)
        assertEquals("$label window logical height follows its exact measured pixels", predicted.second / exact.density, decor.height / density, .001f)
        if (!dialog) {
            assertEquals("Reported host logical width", 369.230769f, decor.width / density, .001f)
            assertEquals("Reported host logical height", 821.538462f, decor.height / density, .001f)
        }
        assertTrue("$label actual content must remain nonempty", content.width > 0 && content.height > 0)
        assertTrue("$label content must fit in actual window", inside(viewBoundsInWindow(content), viewBoundsInWindow(decor)))
        assertTrue("$label production Compose root must remain nonempty", physical.width > 0 && physical.height > 0)
        assertTrue("$label Compose bounds must fit actual content, not be stretched by the fixture",
            inside(physical, viewBoundsInWindow(content)))
        assertEquals("$label actual Compose logical width", physical.width / exact.density,
            (logical.right - logical.left).value, .001f)
        assertEquals("$label actual Compose logical height", physical.height / exact.density,
            (logical.bottom - logical.top).value, .001f)
        assertEquals("$label receives separate status/gesture values, not their sum",
            Triple(exact.statusTopPx, exact.gestureTopPx, exact.bottomPx), injectedInsetObservations[label])
        assertNotNull("$label default DecorView inset handler must run", decorAppliedInsetObservations[label])
        if (!dialog) assertEquals("Root production composition observes the supplied insets",
            Triple(exact.statusTopPx, exact.gestureTopPx, exact.bottomPx), rootComposeInsets)
    }

    private fun assertExactTargetSafety(retryIndex: Int? = null) {
        val exact = checkNotNull(exactViewport)
        assertViewerTargets(retryIndex)
        val actualWindow = viewBoundsInWindow(requireNotNull(currentDialog().window).decorView)
        val controls = listOf("media-back", "media-previous", "media-next") +
            listOfNotNull(retryIndex?.let { "media-viewer-retry-$it" })
        controls.forEach { tag ->
            val bounds = dialogTag(tag).fetchSemanticsNode().boundsInWindow
            assertTrue("$tag must also fit the actual quantized Dialog window", bounds.left >= actualWindow.left &&
                bounds.top >= actualWindow.top && bounds.right <= actualWindow.right && bounds.bottom <= actualWindow.bottom)
            assertTrue("$tag full 48dp target must clear status/viewPadding", bounds.top >= exact.statusTopPx)
            assertTrue("$tag pointer centre must clear the separate system-gesture top region", bounds.center.y >= exact.gestureTopPx)
            assertTrue("$tag must stay inside the exact bottom safe area", bounds.bottom <= exact.heightPx - exact.bottomPx)
            assertTrue("$tag must fit horizontally", bounds.left >= 0 && bounds.right <= exact.widthPx)
        }
    }

    /** Mirrors the pinned Compose 1.7.6 DialogLayout.getDisplayWidth/getDisplayHeight bytecode.
     * It deliberately uses the current Dialog resources, not user-reported fractional dp values.
     */
    private fun predictedProductionDialogSize(decor: View): Pair<Int, Int> {
        val configuration = decor.resources.configuration
        val resourceDensity = decor.resources.displayMetrics.density
        return Math.round(configuration.screenWidthDp * resourceDensity) to
            Math.round(configuration.screenHeightDp * resourceDensity)
    }

    @Suppress("DEPRECATION")
    private fun windowEnvironmentMetrics(window: Window): JsonObject {
        val decor = window.decorView
        val configuration = decor.resources.configuration
        val metrics = decor.resources.displayMetrics
        val display = ShadowDisplay.getDefaultDisplay()
        val appSize = android.graphics.Point().also(display::getSize)
        val realSize = android.graphics.Point().also(display::getRealSize)
        val reportedMetrics = android.util.DisplayMetrics().also(display::getMetrics)
        val realMetrics = android.util.DisplayMetrics().also(display::getRealMetrics)
        val windowManager = requireNotNull(window.context.getSystemService(WindowManager::class.java))
        val windowBounds = windowManager.currentWindowMetrics.bounds
        val maximumBounds = windowManager.maximumWindowMetrics.bounds
        val expected = predictedProductionDialogSize(decor)
        return buildJsonObject {
            put("configurationSource", "Current window.decorView.resources.configuration")
            put("integerConfigurationProvenance", "Synthetic w369dp/h822dp fixture qualifiers, not measured user Android Configuration")
            put("screenWidthDp", configuration.screenWidthDp); put("screenHeightDp", configuration.screenHeightDp)
            put("smallestScreenWidthDp", configuration.smallestScreenWidthDp); put("configurationDensityDpi", configuration.densityDpi)
            put("resourceDisplayMetricsSource", "Current window.decorView.resources.displayMetrics")
            put("resourceWidthPixels", metrics.widthPixels); put("resourceHeightPixels", metrics.heightPixels)
            put("resourceDensity", metrics.density); put("resourceDensityDpi", metrics.densityDpi)
            put("defaultDisplayId", display.displayId)
            put("displayGetSizeWidthPx", appSize.x); put("displayGetSizeHeightPx", appSize.y)
            put("displayGetRealSizeWidthPx", realSize.x); put("displayGetRealSizeHeightPx", realSize.y)
            put("displayMetricsWidthPx", reportedMetrics.widthPixels); put("displayMetricsHeightPx", reportedMetrics.heightPixels)
            put("displayRealMetricsWidthPx", realMetrics.widthPixels); put("displayRealMetricsHeightPx", realMetrics.heightPixels)
            put("displayRealMetricsDensity", realMetrics.density)
            put("windowMetricsSource", "WindowManager from current window.context; observed, not overridden")
            put("currentWindowMetricsBoundsPx", androidBoundsJson(windowBounds))
            put("maximumWindowMetricsBoundsPx", androidBoundsJson(maximumBounds))
            put("pinnedDialogSizingRule", "Compose 1.7.6: Math.round(configuration.screenWidthDp/HeightDp * resource displayMetrics.density)")
            put("predictedProductionDialogWidthPx", expected.first)
            put("predictedProductionDialogHeightPx", expected.second)
        }
    }

    private fun androidBoundsJson(rect: android.graphics.Rect) = buildJsonObject {
        put("left", rect.left); put("top", rect.top); put("right", rect.right); put("bottom", rect.bottom)
        put("width", rect.width()); put("height", rect.height())
    }

    private fun policyJson(policy: WindowPolicyObservation) = buildJsonObject {
        policy.declaredDialogDecorFits?.let { put("declaredDialogPropertiesDecorFitsSystemWindows", it) }
        put("effectiveDecorFitsSystemWindows", policy.effectiveDecorFits)
        put("platformEdgeToEdgeEnforced", policy.edgeToEdgeEnforced)
        put("actualTargetSdk", policy.targetSdk)
        put("actualRuntimeSdk", android.os.Build.VERSION.SDK_INT)
    }

    private fun exactViewportMetrics(dialog: Boolean): JsonObject {
        val exact = checkNotNull(exactViewport)
        val window = if (dialog) requireNotNull(currentDialog().window) else compose.activity.window
        val view = window.decorView
        val content = window.findViewById<View>(android.R.id.content)
        val root = if (dialog) compose.onNode(isDialog()) else compose.onRoot()
        val rootPx = root.fetchSemanticsNode().boundsInWindow
        val rootDp = root.getUnclippedBoundsInRoot()
        val manager = compose.activity.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        return buildJsonObject {
            put("provenance", "Synthetic mapping of reported Flutter window; not a native-device measurement or reproduction")
            put("reportedHostWidthPx", exact.widthPx); put("reportedHostHeightPx", exact.heightPx)
            put("actualAndroidWidthPx", view.width); put("actualAndroidHeightPx", view.height)
            put("windowEnvironment", windowEnvironmentMetrics(window))
            put("fixtureResizesDialog", false)
            put("currentWindowIsProductionDialog", dialog)
            if (dialog) {
                val predicted = predictedProductionDialogSize(view)
                put("predictedDialogWidthPx", predicted.first); put("predictedDialogHeightPx", predicted.second)
                put("dialogQuantizationDeltaWidthPx", predicted.first - exact.widthPx)
                put("dialogQuantizationDeltaHeightPx", predicted.second - exact.heightPx)
                put("actualDialogDeltaWidthPx", view.width - exact.widthPx)
                put("actualDialogDeltaHeightPx", view.height - exact.heightPx)
                put("dialogSizingTolerancePx", 0)
            }
            put("actualDecorFitsSystemWindows", decorFits(window))
            put("fixtureOverridesDialogDecorFitPolicy", false)
            put("windowPolicyBeforeInjection", policyJson(checkNotNull(windowPoliciesBefore[if (dialog) "dialog" else "activity"])))
            put("windowPolicyAfterInjection", policyJson(checkNotNull(windowPoliciesAfter[if (dialog) "dialog" else "activity"])))
            put("windowPolicyAtCapture", policyJson(observeWindowPolicy(window, if (dialog) "dialog" else "activity")))
            put("actualWindowBoundsPx", boundsJson(viewBoundsInWindow(view)))
            put("actualContentBoundsInWindowPx", boundsJson(viewBoundsInWindow(content)))
            put("actualComposeRootInWindowPx", boundsJson(rootPx))
            put("actualWindowLogicalWidthDp", view.width / density)
            put("actualWindowLogicalHeightDp", view.height / density)
            put("actualLogicalWidthDp", (rootDp.right - rootDp.left).value)
            put("actualLogicalHeightDp", (rootDp.bottom - rootDp.top).value)
            put("actualAndroidDensity", view.resources.displayMetrics.density)
            put("reportedStatusViewPaddingTopPx", exact.statusTopPx)
            put("reportedSystemGesturesTopPx", exact.gestureTopPx)
            put("reportedStatusViewPaddingTopDp", exact.statusTopPx / exact.density)
            put("reportedSystemGesturesTopDp", exact.gestureTopPx / exact.density)
            put("reportedBottomPx", exact.bottomPx); put("reportedViewInsetsPx", 0)
            put("statusAndGestureTopsAreIndependentNotSummed", true)
            put("windowInsetDispatchObservations", buildJsonObject {
                injectedInsetObservations.forEach { (label, value) -> put(label, buildJsonObject {
                    put("statusTopPx", value.first); put("systemGesturesTopPx", value.second); put("navigationBottomPx", value.third)
                }) }
            })
            put("defaultDecorHandlerReturnedInsets", buildJsonObject {
                decorAppliedInsetObservations.forEach { (label, value) -> put(label, buildJsonObject {
                    put("statusTopPx", value.first); put("systemGesturesTopPx", value.second); put("navigationBottomPx", value.third)
                }) }
            })
            if (dialog) put("targetBoundsInWindowPx", buildJsonObject {
                for (tag in listOf("media-back", "media-previous", "media-next", "media-viewer-retry-2")) {
                    compose.onAllNodes(hasTestTag(tag) and hasAnyAncestor(isDialog())).fetchSemanticsNodes().singleOrNull()?.let {
                        put(tag, boundsJson(it.boundsInWindow))
                    }
                }
            })
            put("rootComposeStatusTopPx", rootComposeInsets.first)
            put("rootComposeSystemGesturesTopPx", rootComposeInsets.second)
            put("rootComposeSafeBottomPx", rootComposeInsets.third)
            put("reportedAccessibleNavigation", true); put("reportedDisableAnimations", false)
            put("fixtureTouchExplorationEnabled", manager.isTouchExplorationEnabled)
            put("accessibilityAndAnimationFlagsOverriddenByFixture", false)
            put("targetSafetyPolicy", "Synthetic tap check in window coordinates only, not physical gesture safety: full target clears status/viewPadding; pointer centre clears systemGestures; regions are not added")
        }
    }

    private fun imageUrl(index: Int) = "https://image.coolapk.com/synthetic-gallery-$index@800x600.png"
    private fun inside(inner: Rect, outer: Rect) = inner.left >= outer.left - 1f && inner.top >= outer.top - 1f &&
        inner.right <= outer.right + 1f && inner.bottom <= outer.bottom + 1f

    /** Actual view pixels and measured semantics bounds accompany each screenshot. */
    private fun capture(name: String, dialog: Boolean) {
        compose.waitForIdle()
        if (dialog) compose.onAllNodes(isDialog()).assertCountEquals(1)
        else compose.onAllNodes(isDialog()).assertCountEquals(0)
        exactViewport?.let { assertExactWindow(dialog) }
        val measured = buildJsonObject {
            if (dialog) {
                put("dialog", boundsJson(compose.onNode(isDialog()).fetchSemanticsNode().boundsInRoot))
                put("actualViewerText", measureViewerText())
            }
            sourceEntryTag?.let { tag -> put("sourceGeometry", buildJsonObject {
                measureSourceGeometry(tag).forEach { (key, bounds) -> put(key, boundsJson(bounds)) }
            }) }
            for (tag in listOf("content-list", "media-gallery", "media-page-count", "media-back", "media-previous", "media-next", "media-viewer-retry-2", "media-more", "media-cell-2", "media-cell-5")) {
                compose.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().singleOrNull()?.let {
                    put(tag, boundsJson(it.boundsInRoot))
                }
            }
            if (dialog) for (label in listOf("返回", "上一张", "下一张", "重试本图", "重试")) {
                compose.onAllNodes(hasText(label) and hasAnyAncestor(isDialog())).fetchSemanticsNodes().singleOrNull()?.let {
                    put("text:$label", boundsJson(it.boundsInRoot))
                }
            }
        }
        val (bitmap, location) = compose.runOnUiThread {
            val view = if (dialog) requireNotNull(currentDialog().window).decorView
                else compose.activity.findViewById<View>(android.R.id.content)
            check(view.width > 0 && view.height > 0)
            val position = IntArray(2).also(view::getLocationOnScreen)
            Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888).also { view.draw(Canvas(it)) } to position
        }
        val directory = File("build/verification/gallery").apply { mkdirs() }
        try {
            File(directory, "root-$name.png").outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            val metrics = buildJsonObject {
                put("captureTarget", if (dialog) "Sole shown production Dialog.window.decorView, not the underlying Activity" else "NativeApp Activity content View")
                put("composition", "Unmodified native view pixels; no synthetic renderer or compositing")
                put("sourceKind", sourceKind); put("imageCount", sourceCount)
                put("widthPx", bitmap.width); put("heightPx", bitmap.height)
                put("windowXpx", location[0]); put("windowYpx", location[1])
                put("effectiveDensity", density); put("requestedFontScale", fontScale)
                put("actualRootComposeFontScale", actualRootFontScale)
                put("actualActivityResourceFontScale", compose.activity.resources.configuration.fontScale)
                put("actualApplicationResourceFontScale", compose.activity.application.resources.configuration.fontScale)
                put("actualRobolectricSystemFontScale", RuntimeEnvironment.getFontScale())
                put("homeFirstVisibleItemIndex", homeState.firstVisibleItemIndex)
                put("homeFirstVisibleItemScrollOffsetPx", homeState.firstVisibleItemScrollOffset)
                sourcePosition?.let { put("expectedSourceIndex", it.first); put("expectedSourceOffsetPx", it.second) }
                sourceEntryTag?.let { put("sourceEntryTag", it) }
                put("sourceBodyReadyText", sourceBodyText)
                sourceGeometry?.let { geometry -> put("expectedSourceGeometryPx", buildJsonObject {
                    geometry.forEach { (key, bounds) -> put(key, boundsJson(bounds)) }
                }) }
                put("homeRequests", requests.count { it == "home" })
                put("actualBoundsPx", measured)
                exactViewport?.let { put("exactViewport", exactViewportMetrics(dialog)) }
            }
            File(directory, "root-$name.metrics.json").writeText(Json { prettyPrint = true }.encodeToString(JsonObject.serializer(), metrics))
        } finally { bitmap.recycle() }
    }

    private fun boundsJson(rect: Rect) = buildJsonObject {
        put("left", rect.left); put("top", rect.top); put("right", rect.right); put("bottom", rect.bottom)
        put("width", rect.width); put("height", rect.height)
    }
}
