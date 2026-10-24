// SPDX-License-Identifier: AGPL-3.0-only
package io.github.ktclat.c001apk.nativeapp.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import io.github.ktclat.c001apk.nativeapp.domain.*
import kotlinx.coroutines.awaitCancellation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Real Compose/View pixels over a hostile magenta parent; no live account or network. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w393dp-h852dp-420dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PersonalListBackgroundTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private enum class State { GUEST, LOADING, EMPTY, ERROR, POPULATED }

    @Test fun lightThemePaintsEveryPersonalListState() = verify(dark = false)
    @Test fun darkThemePaintsEveryPersonalListState() = verify(dark = true)

    private fun verify(dark: Boolean) {
        var state by mutableStateOf(State.GUEST)
        val background = if (dark) Color(0xff152329) else Color(0xffe5f4f2)
        val colors = if (dark) darkColorScheme(background = background) else lightColorScheme(background = background)
        compose.setContent {
            MaterialTheme(colorScheme = colors) {
                // Without the route-owned Surface the bottom pixels remain magenta, not the theme.
                Box(Modifier.fillMaxSize().background(Color.Magenta)) {
                    val repository = remember(state) {
                        val captured = state
                        PersonalListRepository { _, _, _ ->
                            when (captured) {
                                State.GUEST -> error("Guest may not request personal data")
                                State.LOADING -> awaitCancellation()
                                State.EMPTY -> ApiResult.Success(Page(emptyList(), null))
                                State.ERROR -> ApiResult.Failure(FailureKind.NETWORK, "合成加载失败")
                                State.POPULATED -> ApiResult.Success(Page(listOf(PersonalEntry("8", ContentItem("8", "collection"), "合成收藏夹", "", PersonalDestination.Unsupported)), null))
                            }
                        }
                    }
                    PersonalListScreen(repository, PersonalListRequest(PersonalListKind.COLLECTION),
                        if (state == State.GUEST) null else "synthetic-123", state.ordinal.toLong(), {}, {}, {})
                }
            }
        }
        State.entries.forEach { next ->
            compose.runOnIdle { state = next }
            when (next) {
                State.GUEST -> compose.onNodeWithText("登录").assertIsDisplayed()
                State.LOADING -> compose.onNodeWithTag("personal-loading").assertIsDisplayed()
                State.EMPTY -> compose.onNodeWithText("暂无内容").assertIsDisplayed()
                State.ERROR -> compose.onNodeWithText("合成加载失败").assertIsDisplayed()
                State.POPULATED -> compose.onNodeWithText("合成收藏夹").assertIsDisplayed()
            }
            compose.onNodeWithTag("personal-screen-background").assertIsDisplayed()
            val bitmap = compose.runOnUiThread {
                val view = compose.activity.findViewById<View>(android.R.id.content)
                assertTrue("The actual window must be laid out", view.width > 0 && view.height > 0)
                Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888).also { view.draw(Canvas(it)) }
            }
            try {
                // Clear body regions below the synthetic short content, not the toolbar/card surface.
                for (x in listOf(.1f, .5f, .9f)) for (y in listOf(.75f, .85f, .95f)) {
                    assertEquals("${if(dark) "dark" else "light"}/$next body pixel ($x,$y)",
                        background.toArgb(), bitmap.getPixel((bitmap.width * x).toInt(), (bitmap.height * y).toInt()))
                }
                if (next == State.POPULATED) {
                    val output = File("build/verification/personal-list-background-${if(dark) "dark" else "light"}-synthetic.png")
                    requireNotNull(output.parentFile).mkdirs()
                    output.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
                }
            } finally { bitmap.recycle() }
        }
    }
}
