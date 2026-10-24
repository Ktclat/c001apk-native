// SPDX-License-Identifier: AGPL-3.0-only
package io.github.ktclat.c001apk.nativeapp.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import coil.ImageLoader
import coil.decode.DataSource
import coil.intercept.Interceptor
import coil.request.*
import io.github.ktclat.c001apk.nativeapp.data.ApiDecoder
import io.github.ktclat.c001apk.nativeapp.domain.ContentItem
import io.github.ktclat.c001apk.nativeapp.feature.media.LocalMediaImageLoader
import io.github.ktclat.c001apk.nativeapp.settings.*
import kotlinx.serialization.json.Json
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.util.concurrent.ConcurrentHashMap

internal fun iconFixture(count: Int): ContentItem = if (count == 0) ContentItem("icons", "card", entityTemplate = "iconLinkGridCard") else
    ApiDecoder.item(Json.parseToJsonElement(requireNotNull(IconLinkGridCardTest::class.java.getResource("/icon-grid/count-$count.json")).readText()))

/** Every image request terminates here: original local PNGs, no sockets or placeholder-only success. */
internal class IconFixtureImages(context: android.content.Context) : AutoCloseable {
    val loaded = ConcurrentHashMap.newKeySet<String>()
    val unexpected = ConcurrentHashMap.newKeySet<String>()
    val loader = ImageLoader.Builder(context).components { add(object : Interceptor {
        override suspend fun intercept(chain: Interceptor.Chain): ImageResult {
            val url = chain.request.data.toString()
            val name = url.removePrefix("https://fixture.invalid/")
            if (!url.startsWith("https://fixture.invalid/") || !Regex("icon-([1-9]|10)\\.png").matches(name)) {
                unexpected += url
                return ErrorResult(null, chain.request, IllegalArgumentException("Unknown offline image"))
            }
            val bytes = requireNotNull(javaClass.getResourceAsStream("/icon-grid/$name")).use { it.readBytes() }
            val bitmap = requireNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size))
            loaded += url
            return SuccessResult(BitmapDrawable(context.resources, bitmap), chain.request, DataSource.MEMORY_CACHE)
        }
    }) }.build()
    override fun close() { loader.shutdown(); assertTrue(unexpected.isEmpty()) }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w393dp-h852dp-420dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class IconLinkGridCardTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private lateinit var images: IconFixtureImages
    private val opened = mutableListOf<String>()
    @Before fun prepare() { images = IconFixtureImages(compose.activity) }
    @After fun close() { images.close() }
    @Test fun zero() = verifyCount(0)
    @Test fun one() = verifyCount(1)
    @Test fun four() = verifyCount(4)
    @Test fun five() = verifyCount(5)
    @Test fun six() = verifyCount(6)
    @Test fun ten() = verifyCount(10)
    @Test fun compactLargeFont() = verifyCount(10, 240f, 2f)
    @Test fun fontOnePointSix() = verifyCount(6, 320f, 1.6f)
    @Test @Config(qualifiers = "w852dp-h393dp-land-420dpi") fun landscape() = verifyCount(10, 700f)
    private fun verifyCount(count: Int, width: Float = 373f, scale: Float = 1f) {
        show(mutableStateOf(iconFixture(count)), width, scale)
        if (count == 0) compose.onNodeWithText("暂无可显示的入口").assertIsDisplayed()
        for (index in 0 until count) {
            if (index % 5 == 0 && index > 0) compose.onNodeWithTag("icon-link-page-${index / 5}").performClick()
            val cell = compose.onNodeWithTag("icon-link-entry-$index").assertIsDisplayed().assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp)
            if (index % 5 == 0) {
                compose.waitUntil(5_000) { (index until minOf(index + 5, count)).all { images.loaded.contains("https://fixture.invalid/icon-${it + 1}.png") } }
                capture("count-$count-width-$width-font-$scale-page-${index / 5 + 1}")
            }
            cell.performClick()
            assertEquals(iconFixture(count).children[index].targetUrl, opened.last())
        }
        if (count > 0) compose.waitUntil(5_000) { images.loaded.size >= count }
        assertEquals(count, opened.size)
        capture("count-$count-width-$width-font-$scale")
    }
    @Test fun swipeAndDotSelectionBothReachTail() {
        show(mutableStateOf(iconFixture(6)))
        compose.onNodeWithTag("icon-link-pages").performTouchInput { swipeLeft() }
        compose.onNodeWithTag("icon-link-page-1").assertIsSelected().assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp)
        compose.onNodeWithTag("icon-link-entry-5").performClick()
        compose.onNodeWithTag("icon-link-page-0").performClick().assertIsSelected()
        assertEquals(listOf("/t/入口六"), opened)
    }
    @Test fun leafLinkContractDoesNotInventAnEntityTypeRequirement() {
        val item = iconFixture(1)
        show(mutableStateOf(item.copy(children = listOf(item.children.single().copy(kind = "unknown")))))
        compose.onNodeWithTag("icon-link-entry-0").performClick()
        assertEquals(listOf("/t/入口一"), opened)
    }
    @Test fun shrinkingSecondPageKeepsValidPage() {
        val item = mutableStateOf(iconFixture(10)); show(item)
        compose.onNodeWithTag("icon-link-page-1").performClick().assertIsSelected()
        compose.runOnIdle { item.value = iconFixture(1) }
        compose.onNodeWithTag("icon-link-entry-0").assertIsDisplayed().performClick()
        compose.onNodeWithTag("icon-link-page-1").assertDoesNotExist()
        assertEquals(listOf("/t/入口一"), opened)
    }
    @Test fun extremeTitlesAt240Font2StayBoundedAndClickable() {
        val item = iconFixture(4).let { grid -> grid.copy(children = grid.children.mapIndexed { i, child ->
            child.copy(title = if (i == 0) "😀".repeat(80) else "长".repeat(if (i == 1) 12000 else 1000000))
        }) }
        show(mutableStateOf(item), 240f, 2f)
        repeat(4) { index ->
            val cell = compose.onNodeWithTag("icon-link-entry-$index").assertIsDisplayed().assertWidthIsAtLeast(48.dp)
                .assertHeightIsAtLeast(48.dp)
            assertTrue("Bounded title cell must be at most130dp high", cell.fetchSemanticsNode().boundsInRoot.height /
                compose.activity.resources.displayMetrics.density <= 130f)
            cell.performClick()
        }
        assertEquals(4, opened.size)
        assertEquals(3, compose.onAllNodesWithContentDescription("长".repeat(80) + "，标题过长，已缩略").fetchSemanticsNodes().size)
        compose.waitUntil(5_000) { images.loaded.size >= 4 }
        capture("extreme-title-240-font2")
    }
    @Test fun malformedAndNestedAreVisibleButNeverDispatched() {
        val base = iconFixture(4)
        val item = base.copy(childrenIncomplete = true, children = listOf(
            base.children[0].copy(targetUrl = "javascript:alert(1)"),
            base.children[1].copy(targetUrl = "https://user:pass@coolapk.com/feed/1"),
            base.children[2].copy(children = listOf(base.children[0])),
            base.children[3].copy(targetUrl = "%bad")))
        show(mutableStateOf(item))
        repeat(4) { compose.onNodeWithTag("icon-link-entry-$it").assertIsNotEnabled() }
        compose.onNodeWithText("部分入口数据不完整或超出显示上限").assertIsDisplayed()
        assertTrue(opened.isEmpty()); capture("malformed")
    }
    @Test fun directOversizeProjectionShowsBoundAndWarning() {
        val base = iconFixture(1)
        show(mutableStateOf(base.copy(children = List(101) { base.children[0].copy(id = "$it") })))
        compose.onNodeWithText("部分入口数据不完整或超出显示上限").assertIsDisplayed()
        compose.onNodeWithTag("icon-link-page-19").performScrollTo().performClick()
        compose.onNodeWithTag("icon-link-entry-99").assertIsDisplayed()
        compose.onNodeWithTag("icon-link-entry-100").assertDoesNotExist()
    }
    private fun show(item: MutableState<ContentItem>, width: Float = 373f, scale: Float = 1f) {
        compose.setContent {
            CompositionLocalProvider(LocalMediaImageLoader provides images.loader,
                LocalDensity provides Density(LocalDensity.current.density, scale)) {
                MaterialTheme(colorScheme = flutterReferenceColorScheme(), typography = appTypography(FontChoice.NOTO_SANS)) {
                    androidx.compose.foundation.layout.Box(Modifier.width(width.dp)) { IconLinkGridCard(item.value) { opened += it } }
                }
            }
        }
    }
    private fun capture(name: String) {
        compose.waitForIdle()
        val bitmap = compose.runOnUiThread {
            val view = compose.activity.findViewById<View>(android.R.id.content)
            Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888).also { view.draw(Canvas(it)) }
        }
        File("build/verification/icon-grid/$name.png").apply { parentFile!!.mkdirs() }.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
