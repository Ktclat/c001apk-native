package io.github.ktclat.c001apk.nativeapp.feature.feed

import android.graphics.drawable.BitmapDrawable
import androidx.test.core.app.ApplicationProvider
import coil.request.ImageRequest
import coil.request.SuccessResult
import coil.request.ErrorResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SyntheticFeedImageLoaderTest {
    @Test fun avatarAndLandscapeContainDifferentRealPixels() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        SyntheticFeedImageLoader(context).use { fixture ->
            for ((url, dimensions) in listOf(SyntheticFeedImageLoader.AVATAR to (128 to 128), SyntheticFeedImageLoader.LANDSCAPE to (800 to 400))) {
                val result = fixture.loader.execute(ImageRequest.Builder(context).data(url).build())
                assertTrue(result is SuccessResult)
                val bitmap = ((result as SuccessResult).drawable as BitmapDrawable).bitmap
                assertEquals(dimensions.first, bitmap.width); assertEquals(dimensions.second, bitmap.height)
                assertNotEquals(bitmap.getPixel(0, 0), bitmap.getPixel(bitmap.width / 2, bitmap.height * 3 / 4))
            }
            assertEquals(2, fixture.loadedUrls.size)
            assertTrue(fixture.unexpectedUrls.isEmpty())
        }
    }
    @Test fun unregisteredRequestFailsWithoutNetworkFallback() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        SyntheticFeedImageLoader(context).use { fixture ->
            val url = "https://unregistered.invalid/image.png"
            val result = fixture.loader.execute(ImageRequest.Builder(context).data(url).build())
            assertTrue(result is ErrorResult)
            assertEquals(setOf(url), fixture.unexpectedUrls)
            assertTrue(fixture.loadedUrls.isEmpty())
        }
    }
}
