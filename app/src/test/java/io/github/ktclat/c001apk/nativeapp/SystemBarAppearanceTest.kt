package io.github.ktclat.c001apk.nativeapp

import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Rect
import android.os.Build
import android.view.View
import android.view.Window
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.core.view.WindowCompat
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Window-state regression tests, not a substitute for compositor/device contrast review. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 28, 29, 35], qualifiers = "notnight")
class SystemBarAppearanceTest {
    @Test fun lightStartupThenForcedDarkAndBackUpdatesScrimAndIconsTogether() = withWindow { window ->
        assertAppearance(window, dark = false)
        val policy = WindowPolicy(window)
        for (dark in listOf(true, false, true, true, false, false)) {
            applyNativeSystemBarAppearance(window, dark)
            assertAppearance(window, dark)
            policy.assertUnchanged(window)
        }
    }

    @Test @Config(qualifiers = "night")
    fun darkStartupThenForcedLightAndBackUpdatesScrimAndIconsTogether() = withWindow { window ->
        assertAppearance(window, dark = true)
        val policy = WindowPolicy(window)
        for (dark in listOf(false, true, false, false, true, true)) {
            applyNativeSystemBarAppearance(window, dark)
            assertAppearance(window, dark)
            policy.assertUnchanged(window)
        }
    }

    @Test fun systemNightModeChangesRefreshTheScrimWithoutAnotherEdgeToEdgeCall() = withWindow { window ->
        val policy = WindowPolicy(window)
        for (qualifier in listOf("night", "notnight", "night", "notnight")) {
            RuntimeEnvironment.setQualifiers(qualifier)
            val dark = window.decorView.resources.configuration.uiMode and
                Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
            assertEquals(qualifier == "night", dark)
            applyNativeSystemBarAppearance(window, dark)
            assertAppearance(window, dark)
            policy.assertUnchanged(window)
        }
    }

    @Test @Config(sdk = [29, 35])
    fun newerPlatformNavigationColorsContrastAndGesturePoliciesAreNotOverridden() = withWindow { window ->
        // Sentinels prove this appearance-only helper does not rewrite caller/platform policies.
        // These changes exist only in this synthetic window, never in production code.
        window.navigationBarColor = Color.MAGENTA
        window.statusBarColor = Color.CYAN
        window.decorView.systemGestureExclusionRects = listOf(Rect(1, 2, 3, 4))
        for (contrast in listOf(true, false)) {
            window.isNavigationBarContrastEnforced = contrast
            window.isStatusBarContrastEnforced = contrast
            val policy = WindowPolicy(window)
            for (dark in listOf(true, false, false, true)) {
                applyNativeSystemBarAppearance(window, dark)
                assertIcons(window, dark)
                policy.assertUnchanged(window)
            }
        }
    }

    private fun withWindow(test: (Window) -> Unit) {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        try {
            val activity = controller.get()
            activity.setContentView(FrameLayout(activity))
            activity.enableEdgeToEdge() // Same one-time setup as MainActivity.
            test(activity.window)
        } finally {
            controller.pause().stop().destroy()
        }
    }

    private fun assertAppearance(window: Window, dark: Boolean) {
        assertIcons(window, dark)
        // Independent literals verified against the pinned activity:1.9.3 default setup.
        val expectedNavigationColor = if (Build.VERSION.SDK_INT >= 29) Color.TRANSPARENT
            else if (dark) 0x801B1B1B.toInt() else 0xE6FFFFFF.toInt()
        assertEquals("SDK ${Build.VERSION.SDK_INT}, dark=$dark navigation scrim",
            expectedNavigationColor, window.navigationBarColor)
        assertEquals(Color.TRANSPARENT, window.statusBarColor)
        if (Build.VERSION.SDK_INT >= 29) assertTrue(window.isNavigationBarContrastEnforced)
    }

    private fun assertIcons(window: Window, dark: Boolean) {
        val insets = WindowCompat.getInsetsController(window, window.decorView)
        assertEquals(!dark, insets.isAppearanceLightStatusBars)
        assertEquals(!dark, insets.isAppearanceLightNavigationBars)
    }

    private class WindowPolicy(window: Window) {
        private val navigationColor = window.navigationBarColor
        private val statusColor = window.statusBarColor
        private val windowFlags = window.attributes.flags
        private val layoutFlags = window.decorView.systemUiVisibility and APPEARANCE_FLAGS.inv()
        private val barsBehavior = WindowCompat.getInsetsController(window, window.decorView).systemBarsBehavior
        private val cutoutMode = if (Build.VERSION.SDK_INT >= 28) window.attributes.layoutInDisplayCutoutMode else null
        private val navigationContrast = if (Build.VERSION.SDK_INT >= 29) window.isNavigationBarContrastEnforced else null
        private val statusContrast = if (Build.VERSION.SDK_INT >= 29) window.isStatusBarContrastEnforced else null
        private val gestureExclusions = if (Build.VERSION.SDK_INT >= 29) window.decorView.systemGestureExclusionRects.toList() else null

        fun assertUnchanged(window: Window) {
            assertEquals(statusColor, window.statusBarColor)
            assertEquals(windowFlags, window.attributes.flags)
            assertEquals(layoutFlags, window.decorView.systemUiVisibility and APPEARANCE_FLAGS.inv())
            assertEquals(barsBehavior, WindowCompat.getInsetsController(window, window.decorView).systemBarsBehavior)
            if (Build.VERSION.SDK_INT >= 28) assertEquals(cutoutMode, window.attributes.layoutInDisplayCutoutMode)
            if (Build.VERSION.SDK_INT >= 29) {
                assertEquals(navigationColor, window.navigationBarColor)
                assertEquals(navigationContrast, window.isNavigationBarContrastEnforced)
                assertEquals(statusContrast, window.isStatusBarContrastEnforced)
                assertEquals(gestureExclusions, window.decorView.systemGestureExclusionRects)
            }
        }
    }

    companion object {
        private const val APPEARANCE_FLAGS = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
    }
}
