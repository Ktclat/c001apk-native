// SPDX-License-Identifier: AGPL-3.0-only
package io.github.ktclat.c001apk.nativeapp.feature.media

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog

/** Uses production grid, Dialog and viewer with a controllable Coil interceptor. No real sockets. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w393dp-h852dp-420dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MediaGalleryStateTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val fixtures = mutableListOf<ControlledGalleryImages>()
    private fun fixture() = ControlledGalleryImages(compose.activity).also { fixtures += it }
    @After fun close() { fixtures.forEach { it.close() } }
    private fun url(index: Int) = "https://image.coolapk.com/local-gallery-$index@800x600.jpg"
    private fun awaitState(surface: String, index: Int, label: String) {
        compose.waitUntil(10_000) {
            compose.onAllNodesWithTag("media-$surface-image-$index", useUnmergedTree = true).fetchSemanticsNodes().any {
                it.config.contains(SemanticsProperties.StateDescription) &&
                    it.config[SemanticsProperties.StateDescription] == "图片 ${index + 1}，$label"
            }
        }
    }
    private fun awaitRequests(images: ControlledGalleryImages, url: String, count: Int) {
        try {
            compose.waitUntil(10_000) { images.count(url) == count }
        } catch (failure: ComposeTimeoutException) {
            printDialogDiagnostics(images, "request-timeout")
            throw AssertionError("Expected exactly $count requests for $url; actual=${images.count(url)}; all=${images.requested}", failure)
        }
    }
    /** Sync the newly created production Dialog's first real layout before Coil awaits its size. */
    private fun awaitDialogFrame(images: ControlledGalleryImages, label: String) {
        compose.onAllNodes(isDialog()).assertCountEquals(1)
        compose.onNode(hasTestTag("media-gallery") and hasAnyAncestor(isDialog())).assertIsDisplayed()
        compose.onNode(hasTestTag("media-page-count") and hasAnyAncestor(isDialog())).assertTextEquals("1/1")
        printDialogDiagnostics(images, label)
    }
    private fun printDialogDiagnostics(images: ControlledGalleryImages, label: String) {
        val roots = compose.onAllNodes(isDialog()).fetchSemanticsNodes().map { it.boundsInWindow }
        val windows = compose.runOnUiThread {
            ShadowDialog.getShownDialogs().filter { it.isShowing }.map { dialog ->
                val window = checkNotNull(dialog.window)
                val decor = window.decorView
                val content = window.findViewById<android.view.View>(android.R.id.content)
                "decor=${decor.width}x${decor.height};content=${content.width}x${content.height};attached=${decor.isAttachedToWindow}"
            }
        }
        println("GalleryDialog[$label] shown=${windows.size};windows=$windows;roots=$roots;requested=${images.requested}")
    }
    private fun renderGrid(urls: List<String>, images: ControlledGalleryImages, fallback: ControlledGalleryImages = images) {
        compose.setContent { MaterialTheme { CompositionLocalProvider(LocalMediaImageLoader provides fallback.loader) {
            Box(Modifier.width(310.dp)) { MediaGrid(urls, images.loader) }
        } } }
    }

    @Test fun hangingThumbnailThenSuccessKeepsSourceGeometry() {
        val images = fixture(); val gate = images.next(thumbnailUrl(url(0)))
        renderGrid(listOf(url(0)), images)
        awaitRequests(images, thumbnailUrl(url(0)), 1)
        awaitState("grid", 0, "加载中")
        val before = compose.onNodeWithTag("media-cell-0").getUnclippedBoundsInRoot()
        gate.result.complete(true)
        awaitState("grid", 0, "已加载")
        assertEquals(before, compose.onNodeWithTag("media-cell-0").getUnclippedBoundsInRoot())
        assertEquals(1, images.count(thumbnailUrl(url(0))))
    }

    @Test fun failedThumbnailStillOpensOriginalAndExplicitLoaderReachesDialog() {
        val images = fixture(); val fallback = fixture()
        images.next(thumbnailUrl(url(0))).result.complete(false)
        val original = images.next(url(0))
        renderGrid(listOf(url(0)), images, fallback)
        awaitState("grid", 0, "加载失败")
        compose.onNodeWithTag("media-grid-error-0", useUnmergedTree = true).assertExists()
        compose.onNodeWithTag("media-grid-retry-0").assertDoesNotExist()
        val cell = compose.onNodeWithTag("media-cell-0").getUnclippedBoundsInRoot()
        assertTrue(cell.right - cell.left >= 48.dp && cell.bottom - cell.top >= 48.dp)
        compose.onNodeWithTag("media-cell-0").performTouchInput { click(center) }
        awaitDialogFrame(images, "explicit-loader-first-open")
        awaitRequests(images, url(0), 1)
        awaitState("viewer", 0, "加载中")
        original.result.complete(false)
        awaitState("viewer", 0, "加载失败")
        compose.onNodeWithTag("media-back").assertIsDisplayed()
        assertTrue("Explicit grid loader must cross the real Dialog boundary", fallback.requested.isEmpty())
        val retry = images.next(url(0))
        compose.onNodeWithTag("media-viewer-retry-0").performTouchInput { click(center) }
        awaitRequests(images, url(0), 2)
        awaitState("viewer", 0, "加载中")
        retry.result.complete(true)
        awaitState("viewer", 0, "已加载")
        compose.onNodeWithTag("media-zoom-0").assertIsEnabled().performClick()
        compose.onNodeWithTag("media-fit-0").performClick()
        compose.onNodeWithTag("media-back").performClick()
        compose.onNodeWithTag("media-gallery").assertDoesNotExist()
        awaitState("grid", 0, "加载失败")
        assertEquals(1, images.count(thumbnailUrl(url(0))))
        assertTrue(fallback.requested.isEmpty())
    }

    @Test fun repeatedFailureNeedsAnotherTapAndRevisitingFailedPageDoesNotRetry() {
        val images = fixture(); images.next(url(0)).result.complete(false)
        compose.setContent { MaterialTheme { CompositionLocalProvider(LocalMediaImageLoader provides images.loader) {
            ImageGallery(listOf(url(0), url(1), url(2))) {}
        } } }
        awaitState("viewer", 0, "加载失败")
        val second = images.next(url(0)); second.result.complete(false)
        compose.onNodeWithTag("media-viewer-retry-0").performClick()
        awaitRequests(images, url(0), 2); awaitState("viewer", 0, "加载失败")
        compose.onNodeWithTag("media-next").performClick(); compose.onNodeWithText("2/3").assertExists()
        compose.onNodeWithTag("media-next").performClick(); compose.onNodeWithText("3/3").assertExists()
        compose.onNodeWithTag("media-previous").performClick(); compose.onNodeWithTag("media-previous").performClick()
        awaitState("viewer", 0, "加载失败")
        assertEquals("No automatic retry after composition or page changes", 2, images.count(url(0)))
        compose.onNodeWithTag("media-viewer-retry-0").performClick()
        awaitRequests(images, url(0), 3); awaitState("viewer", 0, "已加载")
    }

    @Test fun oldPageCompletionCannotChangeCurrentPageState() {
        val images = fixture(); val old = images.next(url(0), late = true)
        compose.setContent { MaterialTheme { CompositionLocalProvider(LocalMediaImageLoader provides images.loader) {
            ImageGallery(listOf(url(0), url(1), url(2))) {}
        } } }
        awaitRequests(images, url(0), 1); awaitState("viewer", 0, "加载中")
        compose.onNodeWithTag("media-next").performClick(); compose.onNodeWithTag("media-next").performClick()
        awaitState("viewer", 2, "已加载")
        old.result.complete(false)
        compose.waitUntil(10_000) { images.completed.contains(url(0)) }
        compose.onNodeWithTag("media-page-count").assertTextEquals("3/3")
        awaitState("viewer", 2, "已加载")
        compose.onNodeWithTag("media-viewer-retry-2").assertDoesNotExist()
    }

    @Test fun replacingAndShrinkingImagesResetsOwnerAndClampsPage() {
        val images = fixture(); val old = images.next(url(2), late = true)
        var urls by mutableStateOf(listOf(url(0), url(1), url(2)))
        compose.setContent { MaterialTheme { CompositionLocalProvider(LocalMediaImageLoader provides images.loader) {
            ImageGallery(urls, initialIndex = 2) {}
        } } }
        awaitRequests(images, url(2), 1); awaitState("viewer", 2, "加载中")
        compose.runOnIdle { urls = listOf(url(4)) }
        compose.onNodeWithTag("media-page-count").assertTextEquals("1/1")
        awaitState("viewer", 0, "已加载")
        old.result.complete(false)
        compose.waitUntil(10_000) { images.completed.contains(url(2)) }
        awaitState("viewer", 0, "已加载")
        compose.runOnIdle { urls = emptyList() }
        compose.onNodeWithTag("media-page-count").assertTextEquals("没有图片")
        compose.onNodeWithTag("media-back").assertIsDisplayed()
        compose.onNodeWithTag("media-viewer-retry-0").assertDoesNotExist()
    }

    @Test fun closeWhilePendingThenReopenDoesNotAcceptOldFailure() {
        val images = fixture(); val old = images.next(url(0), late = true)
        renderGrid(listOf(url(0)), images)
        awaitState("grid", 0, "已加载")
        compose.onNodeWithTag("media-cell-0").performClick()
        awaitDialogFrame(images, "late-owner-first-open")
        awaitRequests(images, url(0), 1)
        compose.onNodeWithTag("media-back").performClick()
        compose.onNodeWithTag("media-cell-0").performClick()
        awaitDialogFrame(images, "late-owner-reopen")
        awaitRequests(images, url(0), 2)
        awaitState("viewer", 0, "已加载")
        old.result.complete(false)
        compose.waitUntil(10_000) { images.completed.count { it == url(0) } == 2 }
        awaitState("viewer", 0, "已加载")
        assertEquals(2, images.count(url(0)))
    }
    @Test fun extremeWideSourceKeepsItsImageGeometryInsideAStable48dpTarget() {
        val images = fixture()
        val wide = "https://image.coolapk.com/extreme-local@10000x10.jpg"
        val gate = images.next(thumbnailUrl(wide))
        renderGrid(listOf(wide), images)
        awaitState("grid", 0, "加载中")
        val target = compose.onNodeWithTag("media-cell-0").getUnclippedBoundsInRoot()
        assertEquals(48.dp, target.bottom - target.top)
        gate.result.complete(true); awaitState("grid", 0, "已加载")
        assertEquals(target, compose.onNodeWithTag("media-cell-0").getUnclippedBoundsInRoot())
        assertTrue("The original thin source is not stretched to the touch target", compose.onNodeWithTag("media-grid-content-0", useUnmergedTree = true).getUnclippedBoundsInRoot().let { it.bottom - it.top } < 1.dp)
        compose.onNodeWithTag("media-cell-0").performTouchInput { click(center) }
        compose.onNodeWithTag("media-page-count").assertTextEquals("1/1")
    }

    @Test fun veryNarrowContainerUsesIndependent48dpCellsWithoutHorizontalOverflow() {
        val images = fixture()
        compose.setContent { MaterialTheme { Box(Modifier.width(128.dp)) { MediaGrid((0..8).map(::url), images.loader) } } }
        awaitState("grid", 8, "已加载")
        val bounds = (0..8).map { compose.onNodeWithTag("media-cell-$it").getUnclippedBoundsInRoot() }
        bounds.forEach { assertTrue(it.right - it.left >= 48.dp && it.bottom - it.top >= 48.dp); assertTrue(it.right <= 128.dp) }
        assertEquals(bounds[0].top, bounds[1].top)
        assertTrue(bounds[2].top > bounds[0].bottom)
        compose.onNodeWithTag("media-cell-8").performClick()
        compose.onNodeWithTag("media-page-count").assertTextEquals("9/9")
    }
    @Test fun returningToDisposedSuccessfulPageShowsNewLoadingAndFailureThenExplicitRetry() {
        val images = fixture()
        compose.setContent { MaterialTheme { CompositionLocalProvider(LocalMediaImageLoader provides images.loader) {
            ImageGallery((0..4).map(::url)) {}
        } } }
        awaitState("viewer", 0, "已加载")
        repeat(4) { compose.onNodeWithTag("media-next").performClick() }
        compose.onNodeWithTag("media-page-count").assertTextEquals("5/5")
        compose.onNodeWithTag("media-viewer-image-0", useUnmergedTree = true).assertDoesNotExist()
        val reentry = images.next(url(0))
        val before = images.count(url(0))
        repeat(4) { compose.onNodeWithTag("media-previous").performClick() }
        awaitRequests(images, url(0), before + 1)
        awaitState("viewer", 0, "加载中")
        reentry.result.complete(false)
        awaitState("viewer", 0, "加载失败")
        compose.onNodeWithTag("media-viewer-retry-0").assertIsDisplayed().performClick()
        awaitRequests(images, url(0), before + 2)
        awaitState("viewer", 0, "已加载")
    }
    @Test fun lateUnmountedFailureCannotCompleteTheNewMountOfTheSamePage() {
        val images = fixture(); val old = images.next(url(0), late = true)
        compose.setContent { MaterialTheme { CompositionLocalProvider(LocalMediaImageLoader provides images.loader) {
            ImageGallery((0..4).map(::url)) {}
        } } }
        awaitRequests(images, url(0), 1); awaitState("viewer", 0, "加载中")
        repeat(4) { compose.onNodeWithTag("media-next").performClick() }
        compose.onNodeWithTag("media-viewer-image-0", useUnmergedTree = true).assertDoesNotExist()
        val current = images.next(url(0))
        repeat(4) { compose.onNodeWithTag("media-previous").performClick() }
        awaitRequests(images, url(0), 2); awaitState("viewer", 0, "加载中")
        old.result.complete(false)
        compose.waitUntil(10_000) { images.completed.count { it == url(0) } == 1 }
        awaitState("viewer", 0, "加载中")
        compose.onNodeWithTag("media-viewer-retry-0").assertDoesNotExist()
        current.result.complete(true); awaitState("viewer", 0, "已加载")
        assertEquals(2, images.count(url(0)))
    }
}
