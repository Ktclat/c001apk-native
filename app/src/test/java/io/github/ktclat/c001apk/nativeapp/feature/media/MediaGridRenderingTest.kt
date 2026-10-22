// SPDX-License-Identifier: AGPL-3.0-only
package io.github.ktclat.c001apk.nativeapp.feature.media

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.BitmapDrawable
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import coil.ImageLoader
import coil.decode.DataSource
import coil.intercept.Interceptor
import coil.request.ImageResult
import coil.request.SuccessResult
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/** Real Compose geometry and image-content fixtures; no remote images, requests or account container. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w393dp-h852dp-420dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MediaGridRenderingTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @Test fun landscapeSingle() = capture("media-single-landscape.png", listOf("https://fixture.example/landscape@800x400.jpg"), 310f, 155f)
    @Test fun longSingle() = capture("media-single-long.png", listOf("https://fixture.example/long@100x1000.jpg"), 100f, 100f * 22 / 9)
    @Test fun fourImages() = capture("media-four.png", (0..3).map { "https://fixture.example/image$it@800x400.jpg" }, 100f, 100f)
    @Test fun nineImages() = capture("media-nine.png", (0..8).map { "https://fixture.example/image$it@800x400.jpg" }, 100f, 100f)
    private fun capture(name: String, urls: List<String>, width: Float, height: Float) {
        val loaded = AtomicInteger()
        val started = java.util.concurrent.ConcurrentHashMap<String, AtomicInteger>()
        val loader = ImageLoader.Builder(compose.activity).components {
            add(object : Interceptor {
                override suspend fun intercept(chain: Interceptor.Chain): ImageResult {
                    started.computeIfAbsent(chain.request.data.toString()) { AtomicInteger() }.incrementAndGet()
                    val dimensions = sourceImageSize(chain.request.data.toString())
                    val bitmap = Bitmap.createBitmap(dimensions.width.toInt(), dimensions.height.toInt(), Bitmap.Config.ARGB_8888)
                    val canvas = Canvas(bitmap); val paint = Paint(Paint.ANTI_ALIAS_FLAG)
                    canvas.drawColor(Color.rgb(38, 119, 171))
                    paint.color = Color.rgb(244, 186, 67); canvas.drawCircle(bitmap.width * 0.72f, bitmap.height * 0.23f, bitmap.width * 0.12f, paint)
                    paint.color = Color.rgb(49, 145, 99); canvas.drawRect(0f, bitmap.height * 0.6f, bitmap.width.toFloat(), bitmap.height.toFloat(), paint)
                    paint.color = Color.WHITE; paint.textSize = bitmap.width * 0.08f; canvas.drawText("FIXTURE", bitmap.width * 0.12f, bitmap.height * 0.5f, paint)
                    loaded.incrementAndGet()
                    return SuccessResult(BitmapDrawable(compose.activity.resources, bitmap), chain.request, DataSource.MEMORY_CACHE)
                }
            })
        }.build()
        try {
        compose.setContent {
            MaterialTheme {
                Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(24.dp)) {
                    Text("图片排版离线测试", style = MaterialTheme.typography.titleLarge)
                    Spacer(Modifier.height(12.dp))
                    Box(Modifier.width(310.dp)) { MediaGrid(urls, loader) }
                }
            }
        }
        compose.waitUntil(timeoutMillis = 10_000) { loaded.get() >= urls.size }
        compose.waitForIdle()
        compose.onNodeWithTag("media-cell-0").assertWidthIsEqualTo(width.dp).assertHeightIsEqualTo(height.dp)
        val bitmap = compose.runOnUiThread {
            val view = compose.activity.findViewById<View>(android.R.id.content)
            Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888).also { view.draw(Canvas(it)) }
        }
        val file = File("build/verification/$name"); requireNotNull(file.parentFile).mkdirs()
        file.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        } catch (failure: Throwable) {
            runCatching {
            System.err.println("MEDIA_FIXTURE_FAILURE name=$name completed=${loaded.get()} started=$started expected=${urls.size} mainQueueIdle=${runCatching { android.os.Looper.getMainLooper().queue.isIdle }.getOrNull()}")
            Thread.getAllStackTraces().entries.sortedBy { it.key.name }.take(24).forEach { (thread, stack) ->
                System.err.println("MEDIA_THREAD ${thread.name} state=${thread.state}:\n${stack.take(14).joinToString("\n")}")
            }
            }.onFailure { failure.addSuppressed(it) }
            throw failure
        } finally {
            loader.shutdown()
        }
    }
}
