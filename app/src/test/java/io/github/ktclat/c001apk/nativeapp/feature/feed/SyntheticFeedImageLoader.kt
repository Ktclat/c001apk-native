// SPDX-License-Identifier: AGPL-3.0-only
package io.github.ktclat.c001apk.nativeapp.feature.feed

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.drawable.BitmapDrawable
import coil.ImageLoader
import coil.decode.DataSource
import coil.intercept.Interceptor
import coil.request.ErrorResult
import coil.request.ImageResult
import coil.request.SuccessResult
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

/** Test-only, original geometric artwork. Every request terminates here, including unknown URLs.
 * Never delegates to chain.proceed: accidental remote URLs cannot open a socket.
 * Wrap the real NativeApp in LocalMediaImageLoader provides images.loader, then await loadedUrls.
 */
class SyntheticFeedImageLoader(context: Context) : AutoCloseable {
    companion object {
        const val AVATAR = "https://synthetic.invalid/avatar@128x128.png"
        const val LANDSCAPE = "https://synthetic.invalid/landscape@800x400.png"
        const val LANDSCAPE_ALTERNATE = "https://synthetic.invalid/landscape-alternate@800x400.png"
    }
    val loadedUrls: MutableSet<String> = Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())
    val unexpectedUrls: MutableSet<String> = Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())
    private val resources = context.applicationContext.resources
    val loader: ImageLoader = ImageLoader.Builder(context.applicationContext).components {
        add(object : Interceptor {
            override suspend fun intercept(chain: Interceptor.Chain): ImageResult {
                val url = chain.request.data.toString()
                val bitmap = when (url) {
                    AVATAR -> avatar()
                    LANDSCAPE -> landscape(false)
                    LANDSCAPE_ALTERNATE -> landscape(true)
                    else -> {
                        unexpectedUrls.add(url)
                        return ErrorResult(null, chain.request, IllegalArgumentException("Unregistered offline image fixture"))
                    }
                }
                loadedUrls.add(url)
                return SuccessResult(BitmapDrawable(resources, bitmap), chain.request, DataSource.MEMORY_CACHE)
            }
        })
    }.build()
    override fun close() = loader.shutdown()

    private fun avatar(): Bitmap = Bitmap.createBitmap(128, 128, Bitmap.Config.ARGB_8888).also { bitmap ->
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        canvas.drawColor(Color.rgb(211, 232, 245))
        paint.color = Color.rgb(52, 98, 137)
        canvas.drawCircle(64f, 47f, 25f, paint)
        canvas.drawOval(17f, 78f, 111f, 150f, paint)
        paint.color = Color.rgb(244, 188, 76)
        canvas.drawCircle(99f, 25f, 12f, paint)
        paint.color = Color.WHITE
        canvas.drawCircle(56f, 45f, 3f, paint)
        canvas.drawCircle(72f, 45f, 3f, paint)
    }
    private fun landscape(alternate: Boolean): Bitmap = Bitmap.createBitmap(800, 400, Bitmap.Config.ARGB_8888).also { bitmap ->
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        canvas.drawColor(if (alternate) Color.rgb(222, 183, 176) else Color.rgb(163, 207, 232))
        paint.color = Color.rgb(255, 223, 135)
        canvas.drawCircle(if (alternate) 170f else 620f, 90f, 48f, paint)
        paint.color = Color.rgb(102, 150, 164)
        canvas.drawPath(Path().apply { moveTo(0f, 280f); lineTo(210f, 105f); lineTo(425f, 290f); lineTo(630f, 150f); lineTo(800f, 270f); lineTo(800f, 400f); lineTo(0f, 400f); close() }, paint)
        paint.color = Color.rgb(49, 112, 119)
        canvas.drawPath(Path().apply { moveTo(0f, 330f); lineTo(260f, 245f); lineTo(520f, 325f); lineTo(800f, 240f); lineTo(800f, 400f); lineTo(0f, 400f); close() }, paint)
        paint.color = Color.rgb(225, 241, 234)
        paint.strokeWidth = 5f
        canvas.drawLine(315f, 348f, 505f, 348f, paint)
        canvas.drawLine(370f, 368f, 580f, 368f, paint)
    }
}
