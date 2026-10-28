// SPDX-License-Identifier: AGPL-3.0-only
package io.github.ktclat.c001apk.nativeapp.feature.media

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import coil.ImageLoader
import io.github.ktclat.c001apk.nativeapp.ui.HomeTemplateCard
import io.github.ktclat.c001apk.nativeapp.ui.homeCardTemplate
import io.github.ktclat.c001apk.nativeapp.ui.homeTemplateFixtures
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/** UI interaction checks use closed local transports. They are not live-CDN acceptance. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w393dp-h852dp-420dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PublicImageRecoveryUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private class FailedImages(context: android.content.Context) : AutoCloseable {
        val count = AtomicInteger()
        private val client = publicImageClient().newBuilder().addInterceptor { chain ->
            count.incrementAndGet()
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(567).message("Local test")
                .body("LOCAL ERROR; not service evidence".toResponseBody("text/html".toMediaType())).build()
        }.build()
        val loader = buildPublicMediaImageLoader(context, client).newBuilder().diskCache(null).memoryCache(null).build()
        override fun close() { loader.shutdown(); client.dispatcher.cancelAll(); client.connectionPool.evictAll(); client.dispatcher.executorService.shutdownNow() }
    }
    @Test fun actualCarouselRetryDoesNotNavigateOrAutoLoopAndOtherTapKeepsExactDestination() {
        FailedImages(compose.activity).use { images ->
            val item = homeTemplateFixtures()[0].let { it.copy(children = it.children.take(1)) }
            val opened = mutableListOf<String>()
            compose.setContent { MaterialTheme { CompositionLocalProvider(LocalMediaImageLoader provides images.loader) {
                Box(Modifier.width(373.dp)) { HomeTemplateCard(item, checkNotNull(homeCardTemplate(item)), { opened += it }, {}) }
            } } }
            compose.waitUntil(10_000) { compose.onAllNodesWithTag("public-image-retry", true).fetchSemanticsNodes().isNotEmpty() }
            val retry = compose.onNodeWithTag("public-image-retry", true)
            retry.assertHeightIsAtLeast(48.dp).assertWidthIsAtLeast(48.dp)
            val retainedClick = retry.fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
            retry.performTouchInput { click() }
            compose.waitUntil(10_000) { images.count.get() == 2 && compose.onAllNodesWithText("已重试", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
            assertTrue(opened.isEmpty())
            compose.runOnIdle { retainedClick() }
            compose.waitForIdle()
            assertEquals(2, images.count.get())
            compose.onNodeWithTag("home-entry:${item.id}:0").performTouchInput { click(Offset(5f, 5f)) }
            assertEquals(listOf(item.children.single().targetUrl), opened)
        }
    }
    @Test fun largeFontTallBoxHasVisibleCompleteGlyphAnd48dpAction() {
        FailedImages(compose.activity).use { images ->
            var navigation = 0
            compose.setContent { MaterialTheme { CompositionLocalProvider(LocalMediaImageLoader provides images.loader,
                LocalDensity provides Density(LocalDensity.current.density, 2.4f)) {
                Box(Modifier.size(266.dp, 220.dp).clickable { navigation++ }) {
                    PublicImage("https://image.coolapk.com/local-large.png", modifier = Modifier.size(266.dp, 220.dp).testTag("public-image"), allowRetry = true)
                }
            } } }
            awaitPhase("图片加载失败")
            val button = compose.onNodeWithTag("public-image-retry", true)
            button.assertIsDisplayed().assertHeightIsAtLeast(48.dp).assertWidthIsAtLeast(48.dp)
            val imageBounds = compose.onNodeWithTag("public-image", true).fetchSemanticsNode().boundsInRoot
            val buttonBounds = button.fetchSemanticsNode().boundsInRoot
            val glyphBounds = compose.onNodeWithText("重试一次", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            assertTrue(buttonBounds.left >= imageBounds.left && buttonBounds.right <= imageBounds.right &&
                buttonBounds.top >= imageBounds.top && buttonBounds.bottom <= imageBounds.bottom)
            assertTrue(glyphBounds.left >= buttonBounds.left && glyphBounds.right <= buttonBounds.right &&
                glyphBounds.top >= buttonBounds.top && glyphBounds.bottom <= buttonBounds.bottom)
            capture("large-font-tall")
            button.performTouchInput { click() }
            assertEquals(0, navigation)
        }
    }
    @Test fun largeFontShortBannerAndTinyAvatarHaveNoChildActionAndKeepParentTap() {
        FailedImages(compose.activity).use { images ->
            var opened = 0
            val avatar = mutableStateOf(false)
            compose.setContent { MaterialTheme { CompositionLocalProvider(LocalMediaImageLoader provides images.loader,
                LocalDensity provides Density(LocalDensity.current.density, 2.4f)) {
                val size = if (avatar.value) Modifier.size(32.dp) else Modifier.size(266.dp, 120.dp)
                Box(size.clickable { opened++ }.testTag("parent")) {
                    PublicImage("https://avatar.coolapk.com/local-small.png", modifier = size.testTag("public-image"), avatar = avatar.value, allowRetry = true)
                }
            } } }
            awaitPhase("图片加载失败")
            compose.onNodeWithTag("public-image-retry", true).assertDoesNotExist()
            capture("large-font-short")
            compose.onNodeWithTag("parent").performTouchInput { click() }
            compose.runOnIdle { avatar.value = true }
            awaitPhase("头像加载失败")
            compose.onNodeWithTag("public-image-retry", true).assertDoesNotExist()
            compose.onNodeWithTag("parent").performTouchInput { click() }
            assertEquals(2, opened)
            assertEquals(1, images.count.get())
        }
    }
    @Test fun lateUrlOwnerCannotReplaceNewFailure() = assertReplacedOwner(false)
    @Test fun lateLoaderOwnerCannotReplaceNewFailure() = assertReplacedOwner(true)
    private fun assertReplacedOwner(replaceLoader: Boolean) {
        ControlledGalleryImages(compose.activity).use { first -> ControlledGalleryImages(compose.activity).use { second ->
            val oldUrl = "https://fixture.invalid/old.png"
            val newUrl = if (replaceLoader) oldUrl else "https://fixture.invalid/new.png"
            val late = first.next(oldUrl, late = true)
            val replacement = if (replaceLoader) second else first
            replacement.next(newUrl).result.complete(false)
            val loader = mutableStateOf<ImageLoader>(first.loader); val url = mutableStateOf(oldUrl)
            compose.setContent { MaterialTheme { CompositionLocalProvider(LocalMediaImageLoader provides loader.value) {
                PublicImage(url.value, modifier = Modifier.size(48.dp).testTag("public-image"))
            } } }
            compose.waitUntil(10_000) { first.requested.contains(oldUrl) }
            compose.runOnIdle { loader.value = replacement.loader; url.value = newUrl }
            awaitPhase("图片加载失败")
            late.result.complete(true)
            compose.waitUntil(10_000) { first.completed.contains(oldUrl) }
            compose.waitForIdle(); awaitPhase("图片加载失败")
        } }
    }
    private fun awaitPhase(label: String) = compose.waitUntil(10_000) {
        compose.onAllNodesWithTag("public-image", true).fetchSemanticsNodes().any {
            it.config.contains(SemanticsProperties.StateDescription) && it.config[SemanticsProperties.StateDescription] == label
        }
    }
    private fun capture(name: String) {
        compose.waitForIdle()
        val bitmap = compose.runOnUiThread {
            val view = compose.activity.findViewById<View>(android.R.id.content)
            Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888).also { view.draw(Canvas(it)) }
        }
        File("build/verification/rebuilt-public-image/$name.png").apply { parentFile!!.mkdirs() }
            .outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
}
