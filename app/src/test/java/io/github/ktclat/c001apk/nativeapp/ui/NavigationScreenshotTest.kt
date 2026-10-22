package io.github.ktclat.c001apk.nativeapp.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.activity.ComponentActivity
import android.graphics.Canvas
import android.view.View
import androidx.compose.ui.unit.dp
import android.graphics.Bitmap
import java.io.File
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Synthetic dock rendering evidence, NOT a device/performance or live API validation. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w393dp-h852dp-420dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NavigationScreenshotTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @Test fun portraitDock() = capture("dock-portrait.png")
    @Test @Config(qualifiers = "w852dp-h393dp-land-420dpi") fun landscapeDock() = capture("dock-landscape.png")
    private fun capture(name: String) {
        compose.setContent {
            MaterialTheme {
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                    Column(Modifier.padding(24.dp)) {
                        Text("c001apk Native", style = MaterialTheme.typography.headlineMedium)
                        Spacer(Modifier.height(16.dp))
                        Text("导航组件测试 · 非真机截图", style = MaterialTheme.typography.bodyLarge)
                        Text("图标在上，文字在下；点击区域保持不动。")
                    }
                    FloatingNavigation(0, true, {}, Modifier.align(Alignment.BottomCenter).padding(bottom = 24.dp))
                }
            }
        }
        compose.waitForIdle()
        // captureToImage waits for a hardware-window redraw that Robolectric does not supply.
        // Draw the actual Android Compose view synchronously through the native graphics backend.
        val bitmap = compose.runOnUiThread {
            val view = compose.activity.findViewById<View>(android.R.id.content)
            check(view.width > 0 && view.height > 0)
            Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888).also { view.draw(Canvas(it)) }
        }
        val file = File("build/verification/$name"); file.parentFile.mkdirs()
        file.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
    }
}
