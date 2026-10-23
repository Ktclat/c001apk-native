// SPDX-License-Identifier: AGPL-3.0-only
package io.github.ktclat.c001apk.nativeapp.feature.media

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w393dp-h852dp-420dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ArticleMediaTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun pendingAndDecodedSlotsHaveIdenticalSourceGeometry() {
        val gate = CompletableDeferred<ArticleImageResult>()
        compose.setContent {
            MaterialTheme { CompositionLocalProvider(LocalArticleImageLoader provides ArticleImageLoader { gate.await() }) {
                Box(Modifier.width(310.dp)) { ArticleMedia(url, "原始纯文本说明", 0, listOf(url), "body-owner", null) }
            } }
        }
        compose.onNodeWithTag("article-media-slot-0").assertWidthIsEqualTo(310.dp).assertHeightIsEqualTo(155.dp)
        compose.onNodeWithTag("article-media-source-0", useUnmergedTree = true).assertWidthIsEqualTo(310.dp).assertHeightIsEqualTo(155.dp)
        compose.onNodeWithTag("article-media-0-loading", useUnmergedTree = true).assertExists()
        val pendingHeight = compose.onNodeWithTag("article-media-block-0").fetchSemanticsNode().boundsInRoot.height
        gate.complete(ArticleImageResult.Ready(bitmap()))
        compose.waitUntil(5000) { compose.onAllNodesWithTag("article-media-0-ready", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("article-media-slot-0").assertWidthIsEqualTo(310.dp).assertHeightIsEqualTo(155.dp)
        compose.onNodeWithTag("article-media-source-0", useUnmergedTree = true).assertWidthIsEqualTo(310.dp).assertHeightIsEqualTo(155.dp)
        compose.onNodeWithText("原始纯文本说明").assertExists()
        assertEquals(pendingHeight, compose.onNodeWithTag("article-media-block-0").fetchSemanticsNode().boundsInRoot.height, 0f)
    }

    @Test fun duplicateOccurrencesNavigateWithoutTransferActionsAndClose() {
        val requested = CopyOnWriteArrayList<String>()
        val loader = ArticleImageLoader { requested += it; ArticleImageResult.Ready(bitmap()) }
        compose.setContent {
            MaterialTheme { CompositionLocalProvider(LocalArticleImageLoader provides loader) {
                Box(Modifier.width(310.dp)) { ArticleMedia(url, "", 1, listOf(url, url, null), "body-owner", null) }
            } }
        }
        compose.onNodeWithTag("article-media-1-open").performClick()
        compose.onNodeWithText("图片 2/3").assertExists()
        compose.onNodeWithTag("article-media-viewer-previous").performClick()
        compose.onNodeWithText("图片 1/3").assertExists()
        compose.onNodeWithTag("article-media-viewer-previous").assertIsNotEnabled()
        compose.onNodeWithTag("article-media-viewer-next").performClick()
        compose.onNodeWithTag("article-media-viewer-next").performClick()
        compose.onNodeWithText("图片 3/3").assertExists()
        compose.onNodeWithTag("article-viewer-2-unavailable", useUnmergedTree = true).assertExists()
        compose.onNodeWithTag("article-media-viewer-next").assertIsNotEnabled()
        listOf("保存", "保存全部", "分享", "下载", "复制链接").forEach { compose.onAllNodesWithText(it).assertCountEquals(0) }
        compose.onNodeWithTag("article-media-viewer-close").performClick()
        compose.onNodeWithTag("article-media-viewer").assertDoesNotExist()
        assertTrue(requested.count { it == url } >= 2) // Duplicate slots remain independent occurrences.
    }

    @Test fun failureRetryIsExplicitAndIdentityReplacementCancelsOldJobAndViewer() {
        val calls = AtomicInteger()
        val cancelled = AtomicInteger()
        val identity = mutableStateOf("old-owner-body")
        val loader = ArticleImageLoader {
            when (calls.incrementAndGet()) {
                1 -> ArticleImageResult.Unavailable(ArticleImageFailure.NETWORK)
                2 -> ArticleImageResult.Ready(bitmap())
                else -> try { awaitCancellation() } finally { cancelled.incrementAndGet() }
            }
        }
        compose.setContent {
            MaterialTheme { CompositionLocalProvider(LocalArticleImageLoader provides loader) {
                Box(Modifier.width(310.dp)) { ArticleMedia(url, "", 0, listOf(url), identity.value, null) }
            } }
        }
        compose.waitUntil(5000) { calls.get() == 1 }
        compose.onNodeWithTag("article-media-0-open").performClick()
        compose.onNodeWithTag("article-media-0-retry").performClick()
        compose.onNodeWithTag("article-media-0-details").assertDoesNotExist()
        compose.onNodeWithTag("article-media-0-ready", useUnmergedTree = true).assertExists()
        compose.onNodeWithTag("article-media-0-open").performClick()
        try { compose.waitUntil(5000) {
            // Observe the actual Dialog/loading state first so queued Main work is serviced.
            val viewerVisible = compose.onAllNodesWithTag("article-media-viewer").fetchSemanticsNodes().size == 1
            val loadingVisible = compose.onAllNodesWithTag("article-viewer-0-loading", useUnmergedTree = true)
                .fetchSemanticsNodes().size == 1
            calls.get() == 3 && viewerVisible && loadingVisible
        } }
        catch (failure: ComposeTimeoutException) {
            fun snapshot() = "calls=${calls.get()}, cancelled=${cancelled.get()}, identity=${identity.value}"
            val before = snapshot() // Preserve whether semantics inspection services queued Main work.
            val trees = failureTrees()
            failure.addSuppressed(AssertionError("Retry-to-viewer timeout; before=$before; after=${snapshot()}\n$trees"))
            throw failure // Diagnostics must not rescue the original timeout or exact-count condition.
        }
        compose.onNodeWithTag("article-viewer-0-loading", useUnmergedTree = true).assertExists()
        compose.runOnIdle { identity.value = "new-owner-body" }
        compose.onNodeWithTag("article-media-viewer").assertDoesNotExist()
        compose.onNodeWithTag("article-media-0-ready", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithTag("article-media-0-loading", useUnmergedTree = true).assertExists()
        compose.waitUntil(5000) { cancelled.get() >= 1 }
    }

    @Test fun missingUrlRemainsVisibleAndNeverStartsRequest() {
        val calls = AtomicInteger()
        val original = AtomicInteger()
        compose.setContent {
            MaterialTheme { CompositionLocalProvider(LocalArticleImageLoader provides ArticleImageLoader {
                calls.incrementAndGet(); error("Missing URL must not request bytes")
            }) {
                Box(Modifier.width(310.dp)) { ArticleMedia(null, "仍然显示说明", 0, listOf(null), "body-owner", { original.incrementAndGet() }) }
            } }
        }
        compose.onNodeWithTag("article-media-slot-0").assertHeightIsEqualTo(310.dp)
        compose.onNodeWithTag("article-media-0-unavailable", useUnmergedTree = true).assertExists()
        compose.onNodeWithText("仍然显示说明").assertExists()
        compose.onNodeWithTag("article-media-0-open").performClick()
        compose.onNodeWithTag("article-media-0-original").performScrollTo().performClick()
        assertEquals(1, original.get())
        assertEquals(0, calls.get())
    }

    @Test
    @Config(qualifiers = "w320dp-h480dp-420dpi")
    fun compactLargeFontThinImageRetainsSourceAndWholeBlockGeometryAndReachableActions() {
        val extreme = "https://image.coolapk.com/wide@1000000000x1.jpg"
        val identity = mutableStateOf(0)
        val fontScale = mutableStateOf(1.6f)
        var gate = CompletableDeferred<ArticleImageResult>()
        val original = AtomicInteger()
        compose.setContent {
            val density = LocalDensity.current
            MaterialTheme { CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale.value),
                LocalArticleImageLoader provides ArticleImageLoader { gate.await() }) {
                Box(Modifier.width(288.dp)) { ArticleMedia(extreme, "纯文本说明", 0, listOf(extreme), identity.value,
                    { original.incrementAndGet() }) }
            } }
        }
        val states = listOf(ArticleImageResult.Ready(bitmap())) + ArticleImageFailure.entries.map {
            ArticleImageResult.Unavailable(it)
        }
        listOf(1.6f, 2f).forEachIndexed { scaleIndex, scale ->
            states.forEachIndexed { index, result ->
                val lifetime = scaleIndex * states.size + index
                if (lifetime > 0) compose.runOnIdle {
                    gate = CompletableDeferred(); fontScale.value = scale; identity.value = lifetime
                }
                compose.onNodeWithTag("article-media-0-loading", useUnmergedTree = true).assertExists()
                compose.onNodeWithTag("article-media-slot-0").assertHeightIsEqualTo(48.dp)
                compose.onNodeWithTag("article-media-source-0", useUnmergedTree = true).assertHeightIsEqualTo(0.dp)
                compose.onNodeWithTag("article-media-0-open").assertIsDisplayed().assertWidthIsEqualTo(288.dp).assertHeightIsAtLeast(48.dp)
                    .assertContentDescriptionEquals("图片 1，正在加载静态图片，查看说明")
                val before = compose.onNodeWithTag("article-media-block-0").fetchSemanticsNode().boundsInRoot
                val layouts = mutableListOf<TextLayoutResult>()
                compose.onNodeWithTag("article-media-0-loading", useUnmergedTree = true)
                    .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
                assertTrue(layouts.isNotEmpty())
                assertTrue(layouts.all { it.lineCount == 1 && !it.didOverflowHeight }) // Horizontal ellipsis is allowed.
                compose.onNodeWithTag("article-media-0-open").performClick()
                compose.onNodeWithTag("article-media-0-details").assertExists()
                gate.complete(result)
                compose.waitUntil(5000) { compose.onAllNodesWithTag("article-media-0-loading", useUnmergedTree = true).fetchSemanticsNodes().isEmpty() }
                assertEquals(before, compose.onNodeWithTag("article-media-block-0").fetchSemanticsNode().boundsInRoot)
                compose.onNodeWithTag("article-media-source-0", useUnmergedTree = true).assertHeightIsEqualTo(0.dp)
                if (result is ArticleImageResult.Ready) {
                    compose.onNodeWithTag("article-media-0-open").assertContentDescriptionEquals("图片 1，静态图片已显示，查看图片")
                    compose.onNodeWithTag("article-media-0-status", useUnmergedTree = true).assertExists()
                }
                compose.onNodeWithTag("article-media-0-view").performScrollTo().assertIsDisplayed().performClick()
                compose.onNodeWithTag("article-media-viewer").assertExists()
                compose.onNodeWithTag("article-viewer-0-open").assertIsDisplayed().assertHeightIsAtLeast(48.dp).performClick()
                compose.onNodeWithTag("article-viewer-0-original").performScrollTo().assertIsDisplayed().performClick()
                compose.onNodeWithTag("article-viewer-0-details").assertDoesNotExist()
                compose.onNodeWithTag("article-media-viewer-close").performClick()
                assertEquals(before, compose.onNodeWithTag("article-media-block-0").fetchSemanticsNode().boundsInRoot)
                if (result is ArticleImageResult.Ready) {
                    compose.onNodeWithTag("article-media-0-open").performClick() // Ready shortcut skips details.
                    compose.onNodeWithTag("article-media-viewer").assertExists()
                    compose.onNodeWithTag("article-media-viewer-close").performClick()
                }
            }
        }
        assertEquals(states.size * 2, original.get())
    }

    @Test fun unexpectedLoaderExceptionIsVisibleAndOnlyExplicitRetryRestartsIt() {
        val calls = AtomicInteger()
        compose.setContent {
            MaterialTheme { CompositionLocalProvider(LocalArticleImageLoader provides ArticleImageLoader {
                calls.incrementAndGet(); throw IllegalStateException("synthetic loader failure")
            }) { Box(Modifier.width(310.dp)) { ArticleMedia(url, "", 0, listOf(url), "owner", null) } } }
        }
        compose.onNodeWithTag("article-media-0-unavailable", useUnmergedTree = true).assertExists()
        assertEquals(1, calls.get())
        compose.onNodeWithTag("article-media-0-open").performClick()
        compose.onNodeWithText("图片加载失败").assertExists()
        compose.onNodeWithTag("article-media-0-retry").performClick()
        compose.onNodeWithTag("article-media-0-details").assertDoesNotExist()
        compose.waitUntil(5000) { calls.get() == 2 }
        compose.onNodeWithTag("article-media-0-unavailable", useUnmergedTree = true).assertExists()
    }

    @Test fun scopeDepartureClosesDetailsAndViewerAndCancelsPendingWithoutOldCallbacks() {
        val identity = mutableStateOf("owner-body-one")
        val visible = mutableStateOf(true)
        val cancelled = AtomicInteger()
        val calls = AtomicInteger()
        val original = AtomicInteger()
        compose.setContent {
            MaterialTheme { CompositionLocalProvider(LocalArticleImageLoader provides ArticleImageLoader {
                calls.incrementAndGet()
                try { awaitCancellation() } finally { cancelled.incrementAndGet() }
            }) { if (visible.value) Box(Modifier.width(310.dp)) {
                ArticleMedia(url, "", 0, listOf(url), identity.value, { original.incrementAndGet() })
            } } }
        }
        compose.onNodeWithTag("article-media-0-open").performClick()
        compose.onNodeWithTag("article-media-0-details").assertExists()
        compose.runOnIdle { identity.value = "owner-body-two" }
        compose.onNodeWithTag("article-media-0-details").assertDoesNotExist()
        compose.waitUntil(5000) { cancelled.get() == 1 }
        compose.onNodeWithTag("article-media-0-open").performClick()
        compose.onNodeWithTag("article-media-0-view").performClick()
        compose.onNodeWithTag("article-media-viewer").assertExists()
        compose.waitUntil(5000) { calls.get() == 3 }
        compose.runOnIdle { visible.value = false } // Segment departure, without changing body identity.
        compose.onNodeWithTag("article-media-viewer").assertDoesNotExist()
        compose.onNodeWithTag("article-media-0-details").assertDoesNotExist()
        compose.waitUntil(5000) { cancelled.get() == 3 }
        assertEquals(0, original.get())
    }

    private fun failureTrees(): String = runCatching {
        val roots = compose.onAllNodes(isRoot(), useUnmergedTree = true)
        val count = roots.fetchSemanticsNodes().size
        "rootCount=$count\n" + (0 until minOf(count, 8)).joinToString("\n") { index ->
            "root[$index]\n${roots[index].printToString().take(32_000)}"
        }
    }.getOrElse { "tree unavailable: ${it.javaClass.simpleName}: ${it.message}" }

    private fun bitmap(): Bitmap = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
    private companion object { const val url = "https://image.coolapk.com/fixture@800x400.jpg" }
}
