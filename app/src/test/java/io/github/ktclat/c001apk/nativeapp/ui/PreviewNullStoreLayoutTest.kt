// SPDX-License-Identifier: AGPL-3.0-only
package io.github.ktclat.c001apk.nativeapp.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.widget.Magnifier
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.*
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import io.github.ktclat.c001apk.nativeapp.settings.LocalReadingStyles
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implements
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.GraphicsMode

/** Compare the no-cache wrapper to the previous short-body Column layout under narrow constraints. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35], shadows=[PreviewMagnifierShadow::class])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PreviewNullStoreLayoutTest {
    @get:Rule val compose=createAndroidComposeRule<ComponentActivity>()
    @Test fun narrowDefaultFontRetainsSizeSelectionAndLinkDialog() = verify(1f)
    @Test fun narrowLargeFontRetainsSizeSelectionAndLinkDialog() = verify(2f)
    private fun verify(scale: Float) {
        val reference=mutableStateOf(true)
        val text="窄屏正文可选择，包含链接入口"
        var opened: String?=null
        var copied: AnnotatedString?=null
        val clipboard=object: ClipboardManager {
            override fun setText(annotatedString: AnnotatedString) { copied=annotatedString }
            override fun getText(): AnnotatedString? = copied
        }
        var copySelection: (() -> Unit)?=null
        val toolbar=object: TextToolbar {
            override var status=TextToolbarStatus.Hidden
            override fun showMenu(rect: Rect, onCopyRequested: (() -> Unit)?, onPasteRequested: (() -> Unit)?, onCutRequested: (() -> Unit)?, onSelectAllRequested: (() -> Unit)?) {
                status=TextToolbarStatus.Shown; copySelection=onCopyRequested
            }
            override fun hide() { status=TextToolbarStatus.Hidden }
        }
        compose.setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density,scale), LocalPreviewGeometryStore provides null, LocalTextToolbar provides toolbar, LocalClipboardManager provides clipboard) {
                    Box(Modifier.width(240.dp)) {
                        if(reference.value) Column(Modifier.testTag("reference"), verticalArrangement=Arrangement.spacedBy(6.dp)) {
                            SelectionContainer { Text(text,style=LocalReadingStyles.current.body) }
                            TextButton(onClick={}) { Text("本段链接（1）") }
                        } else PagedPostText("窄屏正文可选择，包含链接<a href='/t/test'>入口</a>") { opened=it }
                    }
                }
            }
        }
        val expected=compose.onNodeWithTag("reference").fetchSemanticsNode().size
        compose.runOnIdle { reference.value=false }
        compose.waitUntil(5_000) { compose.onAllNodesWithText("本段链接（1）").fetchSemanticsNodes().isNotEmpty() }
        assertEquals(expected,compose.onNodeWithTag("post-preview-block").fetchSemanticsNode().size)
        compose.onNodeWithText(text).performTouchInput { longClick() }
        compose.onNodeWithText(text).assertExists()
        compose.runOnIdle {
            assertEquals(TextToolbarStatus.Shown,toolbar.status)
            requireNotNull(copySelection) { "Real selection must expose a copy action" }.invoke()
            assertTrue("Copy must contain selected body text", !copied?.text.isNullOrBlank() && text.contains(copied!!.text))
        }
        compose.onNodeWithText("本段链接（1）").performTouchInput { click() }
        compose.onNodeWithText("本段链接").assertIsDisplayed()
        capture("null-store-240-font$scale-dialog")
        compose.onNodeWithText("入口").performClick()
        compose.runOnIdle { assertEquals("/t/test",opened) }
        compose.onNodeWithText("本段链接").assertDoesNotExist()
        capture("null-store-240-font$scale")
    }
    private fun capture(name: String) {
        compose.waitForIdle()
        val bitmap=compose.runOnUiThread {
            val view=compose.activity.findViewById<View>(android.R.id.content)
            Bitmap.createBitmap(view.width,view.height,Bitmap.Config.ARGB_8888).also { view.draw(Canvas(it)) }
        }
        File("build/verification/icon-grid/$name.png").apply { parentFile!!.mkdirs() }.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) }
    }
}

/** Robolectric has no real Surface for PixelCopy. Only the hardware magnifier is inert here;
 * selection gestures, copy state, layout and link routing remain production behavior.
 * This test does not establish real-device magnifier or native-toolbar fidelity.
 */
@Implements(Magnifier::class)
class PreviewMagnifierShadow {
    @Implementation fun show(sourceCenterX: Float, sourceCenterY: Float) = Unit
    @Implementation fun show(sourceCenterX: Float, sourceCenterY: Float, magnifierCenterX: Float, magnifierCenterY: Float) = Unit
    @Implementation fun dismiss() = Unit
}
