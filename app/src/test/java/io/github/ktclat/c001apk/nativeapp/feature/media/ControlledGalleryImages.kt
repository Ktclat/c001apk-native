// SPDX-License-Identifier: AGPL-3.0-only
package io.github.ktclat.c001apk.nativeapp.feature.media

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.drawable.BitmapDrawable
import coil.ImageLoader
import coil.decode.DataSource
import coil.intercept.Interceptor
import coil.request.ErrorResult
import coil.request.ImageResult
import coil.request.SuccessResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList

/** Original geometric fixture artwork, with cancellable or deliberately late completion.
 * Every URL terminates here; never calls chain.proceed and cannot open a network socket.
 */
internal class ControlledGalleryImages(context: Context) : AutoCloseable {
    data class Gate(val result: CompletableDeferred<Boolean> = CompletableDeferred(), val late: Boolean = false)
    private val pending = ConcurrentHashMap<String, ConcurrentLinkedQueue<Gate>>()
    val requested = CopyOnWriteArrayList<String>()
    val completed = CopyOnWriteArrayList<String>()
    private val allGates = CopyOnWriteArrayList<Gate>()
    fun next(url: String, late: Boolean = false): Gate = Gate(late = late).also {
        allGates += it; pending.computeIfAbsent(url) { ConcurrentLinkedQueue() }.add(it)
    }
    fun count(url: String): Int = requested.count { it == url }
    private val resources = context.resources
    private val png = checkNotNull(javaClass.getResourceAsStream("/gallery/original-landscape.png")).use { it.readBytes() }
    val loader = ImageLoader.Builder(context).components {
        add(object : Interceptor {
            override suspend fun intercept(chain: Interceptor.Chain): ImageResult {
                val url = chain.request.data.toString()
                val gate = pending[url]?.poll()
                requested += url
                val success = if (gate == null) true else if (gate.late) withContext(NonCancellable) { gate.result.await() } else gate.result.await()
                completed += url
                if (!success) return ErrorResult(null, chain.request, IOException("Synthetic image failure"))
                val bitmap = checkNotNull(BitmapFactory.decodeByteArray(png, 0, png.size)) { "Local PNG fixture did not decode" }
                return SuccessResult(BitmapDrawable(resources, bitmap), chain.request, DataSource.MEMORY_CACHE)
            }
        })
    }.build()
    override fun close() { allGates.forEach { it.result.complete(false) }; loader.shutdown() }
}
