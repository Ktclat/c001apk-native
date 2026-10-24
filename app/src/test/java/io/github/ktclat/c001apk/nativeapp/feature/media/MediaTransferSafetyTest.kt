// SPDX-License-Identifier: AGPL-3.0-only
package io.github.ktclat.c001apk.nativeapp.feature.media

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okio.Buffer
import okio.Source
import okio.Timeout
import okio.buffer
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MediaTransferSafetyTest {
    @Test fun cancellationDeletesUnreturnedMediaFile() = runBlocking {
        val context = RuntimeEnvironment.getApplication() as Context
        val started = CountDownLatch(1); val release = CountDownLatch(1)
        val body = object : ResponseBody() {
            private val stream = object : Source {
                override fun read(sink: Buffer, byteCount: Long): Long {
                    started.countDown(); release.await(5, TimeUnit.SECONDS)
                    sink.writeUtf8("GIF89a"); return 6
                }
                override fun timeout() = Timeout.NONE
                override fun close() = Unit
            }.buffer()
            override fun contentType() = "image/gif".toMediaType()
            override fun contentLength() = -1L
            override fun source() = stream
        }
        // Interceptor returns synthetic bytes; no socket or external request is made.
        val client = OkHttpClient.Builder().addInterceptor { chain -> Response.Builder().request(chain.request())
            .protocol(Protocol.HTTP_1_1).code(200).message("Fixture").body(body).build() }.build()
        val directory = File(context.cacheDir, "shared-media")
        val before = directory.listFiles()?.map { it.name }?.toSet().orEmpty()
        val job = launch(Dispatchers.Default) { MediaTransfer(context, client).fetch("https://images.example.com/fixture.gif") }
        assertTrue(started.await(5, TimeUnit.SECONDS))
        job.cancel(); release.countDown(); job.join()
        assertEquals(before, directory.listFiles()?.map { it.name }?.toSet().orEmpty())
    }
    @Test fun cancelledOperationCanDiscardOwnedDownload() = runBlocking {
        val context = RuntimeEnvironment.getApplication() as Context
        val file = File.createTempFile("owned-media", ".gif", context.cacheDir).apply { writeText("GIF89a") }
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            try { awaitCancellation() }
            finally { MediaTransfer(context).discard(MediaTransfer.Download(file, "image/gif")) }
        }
        job.cancelAndJoin()
        assertFalse(file.exists())
    }
    @Test fun nonContentSaveCannotWriteToArbitraryFile() = runBlocking {
        val context = RuntimeEnvironment.getApplication() as Context
        val source = File.createTempFile("fixture", ".gif", context.cacheDir).apply { writeText("GIF89a") }
        val target = File(context.filesDir, "must-not-be-written.gif")
        try {
            var rejected = false
            try { MediaTransfer(context).save(MediaTransfer.Download(source, "image/gif"), Uri.fromFile(target)) }
            catch (_: IllegalArgumentException) { rejected = true }
            assertTrue(rejected); assertFalse(target.exists())
        } finally { source.delete(); target.delete() }
    }
}
