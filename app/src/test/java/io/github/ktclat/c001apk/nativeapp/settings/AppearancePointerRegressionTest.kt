// SPDX-License-Identifier: AGPL-3.0-only
package io.github.ktclat.c001apk.nativeapp.settings

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import io.github.ktclat.c001apk.nativeapp.diagnostics.NativeBuildInfo
import kotlinx.serialization.json.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog
import org.robolectric.shadows.ShadowDisplay
import java.io.File

/** Actual pointer delivery to the production Settings dialog, not a SetProgress semantics action. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26], qualifiers = "w360dp-h640dp-320dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AppearancePointerRegressionTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private var oldScale: Float? = null
    private val directory = File("build/verification/appearance-pointer")
    @After fun restore() { oldScale?.let { compose.runOnUiThread { RuntimeEnvironment.setFontScale(it) } } }

    @Test fun freshLeftCenterRightPointerTapsAtSystem100() = verify(1f)
    @Test fun freshLeftCenterRightPointerTapsAtSystem200() = verify(2f)

    private fun verify(systemScale: Float) {
        compose.runOnUiThread {
            oldScale = RuntimeEnvironment.getFontScale(); RuntimeEnvironment.setFontScale(systemScale)
            shadowOf(ShadowDisplay.getDefaultDisplay()).apply {
                setWidth(720); setHeight(1280); setRealWidth(720); setRealHeight(1280)
            }
            compose.activity.window.setLayout(720, 1280)
        }
        // Match the observed API26 preferences, without account, repository or network work.
        val settings = AppSettings(dynamicColor = false, font = FontChoice.SYSTEM, readingPreset = ReadingPreset.ORIGINAL)
        var edits = 0
        compose.setContent {
            val host = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(host.density, host.fontScale * settings.fontScale)) {
                MaterialTheme(colorScheme = fixedColorScheme(0, false), typography = appTypography(settings.font)) {
                    SettingsScreen(settings, {}, {}, {}, {}, {}, {}, onSettingsEdit = { edits++ })
                }
            }
        }
        compose.onNode(hasText("外观与字体") and hasClickAction()).performScrollTo().performClick()
        directory.mkdirs()
        val observations = mutableListOf<JsonObject>()
        val actual = mutableListOf<Pair<String, String>>()
        for (delivery in listOf("click", "down-up-no-advance")) {
        for ((label, fraction, expected) in listOf(Triple("left", .05f, "80%"), Triple("center", .5f, "120%"), Triple("right", .95f, "160%"))) {
            compose.onNodeWithTag("settings-detail-list").performScrollToNode(hasText("应用字号") and hasClickAction())
            compose.onNode(hasText("应用字号") and hasClickAction()).performClick()
            compose.onNodeWithText("预览字号：100%").assertExists()
            val slider = compose.onNodeWithContentDescription("应用字号")
            slider.performScrollTo().assertIsDisplayed()
            val before = slider.fetchSemanticsNode().boundsInRoot
            val name = "system${(systemScale * 100).toInt()}-$delivery-$label"
            capture("$name-before")
            // Use the currently measured node's own coordinates, and a real down/up pair.
            slider.performTouchInput {
                val point = Offset(width * fraction, height / 2f)
                if (delivery == "click") click(point) else { down(point); up() }
            }
            compose.waitForIdle()
            val after = slider.fetchSemanticsNode().boundsInRoot
            val value = compose.onNodeWithTag("font-scale-value").fetchSemanticsNode().config[SemanticsProperties.Text].joinToString { it.text }
            capture("$name-after")
            actual += expected to value
            observations += buildJsonObject {
                put("point", label); put("xFraction", fraction); put("expected", "预览字号：$expected"); put("actual", value)
                put("beforeBounds", before.toString()); put("afterBounds", after.toString())
                put("touchInRootX", before.left + before.width * fraction); put("touchInRootY", before.center.y)
                put("delivery", delivery); put("semanticProgressAction", false)
            }
            compose.onNodeWithContentDescription("关闭应用字号").performClick()
            compose.onNodeWithTag("appearance-detail").assertDoesNotExist()
        }
        }
        val report = buildJsonObject {
            put("syntheticOnly", true); put("systemFontScale", systemScale)
            put("sourceFingerprint", NativeBuildInfo.SOURCE_FINGERPRINT); put("observations", JsonArray(observations))
            put("settingsWrites", edits)
        }
        File(directory, "system${(systemScale * 100).toInt()}-observations.json").writeText(report.toString())
        assertEquals("Pointer preview and Close must not write preferences", 0, edits)
        actual.forEach { (expected, value) -> assertEquals(report.toString(), "预览字号：$expected", value) }
    }

    private fun capture(name: String) {
        compose.waitForIdle()
        val dialog = ShadowDialog.getLatestDialog()
        val bitmap = compose.runOnUiThread {
            val decor = requireNotNull(dialog.window).decorView
            Bitmap.createBitmap(decor.width, decor.height, Bitmap.Config.ARGB_8888).also { decor.draw(Canvas(it)) }
        }
        try { File(directory, "$name.png").outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) } }
        finally { bitmap.recycle() }
    }
}
