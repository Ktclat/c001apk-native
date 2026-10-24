package io.github.ktclat.c001apk.nativeapp

import androidx.activity.ComponentActivity
import androidx.core.view.WindowCompat
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35])
class SystemBarAppearanceTest {
    @Test fun explicitAppThemeUpdatesBothBarAppearanceFlagsWithoutChangingContrastPolicy() {
        if (android.os.Build.VERSION.SDK_INT < 29) error("This resource/appearance fixture requires SDK 29+; keep @Config(sdk=[35])")
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        try {
            val activity = controller.get()
            activity.setContentView(android.widget.FrameLayout(activity))
            val window = activity.window
            val contrast = window.isNavigationBarContrastEnforced
            val insets = WindowCompat.getInsetsController(window,window.decorView)
            applyNativeSystemBarAppearance(window,true)
            assertFalse(insets.isAppearanceLightStatusBars)
            assertFalse(insets.isAppearanceLightNavigationBars)
            applyNativeSystemBarAppearance(window,false)
            assertTrue(insets.isAppearanceLightStatusBars)
            assertTrue(insets.isAppearanceLightNavigationBars)
            assertEquals(contrast,window.isNavigationBarContrastEnforced)
        } finally { controller.pause().stop().destroy() }
    }
}
