package io.github.ktclat.c001apk.nativeapp

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.AdaptiveIconDrawable
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Resource inflation/rendering only; not a launcher or physical-device screenshot. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LauncherResourcesTest {
    @Test fun exactAdaptiveIdentityHasResolvableNormalRoundAndMonochromeLayers() {
        if (android.os.Build.VERSION.SDK_INT < 33) error("This resource/appearance fixture requires SDK 33+; keep @Config(sdk=[35])")
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        assertEquals(R.mipmap.ic_launcher,context.applicationInfo.icon)
        listOf(R.mipmap.ic_launcher to "normal", R.mipmap.ic_launcher_round to "round").forEach { (id,name) ->
            val icon = context.getDrawable(id) as AdaptiveIconDrawable
            assertNotNull(icon.background)
            assertNotNull(icon.foreground)
            val mono = requireNotNull(icon.monochrome)
            val normal = Bitmap.createBitmap(192,192,Bitmap.Config.ARGB_8888)
            icon.setBounds(0,0,192,192); icon.draw(Canvas(normal))
            assertTrue("Adaptive icon must draw visible pixels", (0 until 192).any { x -> Color.alpha(normal.getPixel(x,96)) > 0 })
            val themed = Bitmap.createBitmap(192,192,Bitmap.Config.ARGB_8888)
            // Launcher-style monochrome tint, not new/redrawn artwork.
            mono.mutate().setTint(Color.BLACK); mono.setBounds(0,0,192,192); mono.draw(Canvas(themed))
            assertTrue("Original infinity outline must be nonempty", (0 until 192).any { x -> (0 until 192).any { y -> Color.alpha(themed.getPixel(x,y)) > 0 } })
            File("build/verification").mkdirs()
            File("build/verification/native-launcher-$name-resource-synthetic.png").outputStream().use { normal.compress(Bitmap.CompressFormat.PNG,100,it) }
            File("build/verification/native-launcher-$name-monochrome-resource-synthetic.png").outputStream().use { themed.compress(Bitmap.CompressFormat.PNG,100,it) }
        }
    }
}
