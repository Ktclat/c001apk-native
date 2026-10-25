// SPDX-License-Identifier: AGPL-3.0-only
package io.github.ktclat.c001apk.nativeapp.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import io.github.ktclat.c001apk.nativeapp.domain.ContentItem
import io.github.ktclat.c001apk.nativeapp.feature.media.LocalMediaImageLoader
import io.github.ktclat.c001apk.nativeapp.settings.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w393dp-h852dp-420dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class HomeTemplateCardTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private lateinit var images: IconFixtureImages
    private val opened = mutableListOf<String>()
    private var refreshCount = 0
    @Before fun setup() { images = IconFixtureImages(compose.activity) }
    @After fun close() { images.close() }
    @Test fun oddMiniGridTailRemainsVisibleAndNavigable() {
        val item = homeTemplateFixtures()[2]; show(item)
        compose.onNodeWithText("离线内容 1").assertIsDisplayed()
        repeat(3) { index ->
            compose.onNodeWithTag("home-entry:${item.id}:$index").assertIsDisplayed().assertHeightIsAtLeast(48.dp).performClick()
        }
        assertEquals(listOf("/feed/9100", "/feed/9101", "/feed/9102"), opened)
        capture("mini-grid-normal")
    }
    @Test fun compactLargeFontGridWrapsToOneColumnWithAccessibleTail() {
        val item = homeTemplateFixtures()[2]; show(item, 240f, 2f)
        val a = compose.onNodeWithTag("home-entry:${item.id}:0").fetchSemanticsNode().boundsInRoot
        val b = compose.onNodeWithTag("home-entry:${item.id}:1").fetchSemanticsNode().boundsInRoot
        assertTrue(b.top >= a.bottom)
        compose.onNodeWithTag("home-entry:${item.id}:2").performScrollTo().assertIsDisplayed().performClick()
        assertEquals(listOf("/feed/9102"), opened)
        capture("mini-grid-font2")
    }
    @Test fun carouselNextPreviousAndShrinkingResponseKeepAValidTarget() {
        val initial = homeTemplateFixtures()[0]; val item = mutableStateOf(initial); show(item)
        compose.onNodeWithTag("home-next:${initial.id}").performClick()
        compose.onNodeWithTag("home-entry:${initial.id}:1").assertIsDisplayed().performClick()
        assertEquals(listOf("/feed/9101"), opened)
        compose.onNodeWithTag("home-previous:${initial.id}").performClick()
        compose.onNodeWithTag("home-entry:${initial.id}:0").assertIsDisplayed()
        compose.runOnIdle { item.value = initial.copy(children = initial.children.take(1)) }
        compose.onNodeWithTag("home-next:${initial.id}").assertDoesNotExist()
        compose.onNodeWithTag("home-entry:${initial.id}:0").assertIsDisplayed()
        capture("carousel-single")
    }
    @Test fun emptyCarouselDoesNotCrashAndNeverInventsAnEntry() {
        show(homeTemplateFixtures()[0].copy(children = emptyList()))
        compose.onNodeWithText("暂无可显示的入口").assertIsDisplayed()
        assertTrue(opened.isEmpty())
    }
    @Test fun imageTextScrollTailLoadsRealSyntheticImageAndOpensExactUrl() {
        val item = homeTemplateFixtures()[5]; show(item)
        compose.onNodeWithTag("home-entries:${item.id}").performScrollToIndex(2)
        compose.onNodeWithTag("home-entry:${item.id}:2").assertIsDisplayed().performClick()
        compose.waitUntil(5_000) { images.loaded.contains("https://fixture.invalid/icon-3.png") }
        assertEquals(listOf("/feed/9102"), opened); capture("image-text-tail")
    }
    @Test fun avatarScrollUsesUsernameAndSyntheticAvatar() {
        val item = homeTemplateFixtures()[3]; show(item)
        compose.onNodeWithText("合成用户 1").assertIsDisplayed()
        compose.waitUntil(5_000) { images.loaded.contains("https://fixture.invalid/icon-1.png") }
        compose.onNodeWithTag("home-entry:${item.id}:0").performClick()
        assertEquals(listOf("/feed/9100"), opened)
    }
    @Test fun miniScrollAndSquareScrollAreIndependentlyReachable() {
        val initial = homeTemplateFixtures()[1]; val item = mutableStateOf(initial); show(item)
        compose.onNodeWithTag("home-entry:${initial.id}:0").performClick()
        compose.runOnIdle { item.value = homeTemplateFixtures()[4] }
        compose.onNodeWithTag("home-entry:card-4:0").performClick()
        assertEquals(listOf("/feed/9100", "/feed/9100"), opened)
    }
    @Test fun titleUsesExplicitDestinationMessageUsesDescriptionAndRefreshIsRealAction() {
        val item = mutableStateOf(homeTemplateFixtures()[6]); show(item)
        compose.onNodeWithTag("home-title:card-6").performClick()
        assertEquals(listOf("/page?url=SYNTHETIC_HOME_SECTION"), opened)
        compose.runOnIdle { item.value = homeTemplateFixtures()[7] }
        compose.onNodeWithText("这是一条完整的首页提示，不应变成“不支持模板”。").assertIsDisplayed()
        compose.runOnIdle { item.value = homeTemplateFixtures()[9] }
        compose.onNodeWithTag("home-refresh:card-9").performClick()
        assertEquals(1, refreshCount)
    }
    @Test fun nestedUnsupportedLeafAndTruncatedContainerAreExplicit() {
        val item = homeTemplateFixtures()[2].let { it.copy(childrenIncomplete = true, children = listOf(it.children.first().copy(children = it.children))) }
        show(item)
        compose.onNodeWithTag("home-entry:${item.id}:0").assertIsNotEnabled()
        compose.onNodeWithText("暂不支持此入口").assertIsDisplayed()
        compose.onNodeWithText("部分入口数据不完整或超出显示上限").assertIsDisplayed()
        assertTrue(opened.isEmpty())
    }
    @Test fun darkCardsRetainReadableImageAndTextHierarchy() {
        show(homeTemplateFixtures()[5], dark = true)
        compose.onNodeWithText("离线内容 1").assertIsDisplayed()
        compose.waitUntil(5_000) { images.loaded.contains("https://fixture.invalid/icon-1.png") }
        capture("image-text-dark")
    }
    private fun show(item: ContentItem, width: Float = 373f, scale: Float = 1f, dark: Boolean = false) = show(mutableStateOf(item), width, scale, dark)
    private fun show(item: MutableState<ContentItem>, width: Float = 373f, scale: Float = 1f, dark: Boolean = false) {
        compose.setContent {
            CompositionLocalProvider(LocalMediaImageLoader provides images.loader, LocalDensity provides Density(LocalDensity.current.density, scale)) {
                MaterialTheme(colorScheme = fixedColorScheme(0, dark), typography = appTypography(FontChoice.NOTO_SANS)) {
                    Column(Modifier.width(width.dp).verticalScroll(rememberScrollState())) {
                        key(item.value.id) { HomeTemplateCard(item.value, requireNotNull(homeCardTemplate(item.value)), { opened += it }, { refreshCount++ }) }
                    }
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
        File("build/verification/home-templates/$name.png").apply { parentFile!!.mkdirs() }.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
}
