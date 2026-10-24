// SPDX-License-Identifier: AGPL-3.0-only
package io.github.ktclat.c001apk.nativeapp.social

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.Base64
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ImageBatchLifecycleTest {
    @Test fun cancellingSecondSelectionDeletesBothNewSpoolsAndKeepsExistingDraftImage() = runBlocking {
        val context = RuntimeEnvironment.getApplication() as Context
        // Fixed real PNG; native BitmapFactory performs the same MIME sniff as production.
        val bytes = Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAIAAAADCAYAAAC56t6BAAAAEUlEQVR4nGPYIiLyH4QZMBgAk8MLI2iMZVMAAAAASUVORK5CYII=")
        val oldUri = Uri.parse("content://synthetic/existing")
        shadowOf(context.contentResolver).registerInputStream(oldUri, ByteArrayInputStream(bytes))
        val existing = stageSelectedImage(context, oldUri)
        val first = Uri.parse("content://synthetic/first")
        val second = Uri.parse("content://synthetic/second")
        shadowOf(context.contentResolver).registerInputStream(first, ByteArrayInputStream(bytes))
        val started = CountDownLatch(1); val release = CountDownLatch(1)
        shadowOf(context.contentResolver).registerInputStream(second, object : InputStream() {
            override fun read(): Int { started.countDown(); check(release.await(5, TimeUnit.SECONDS)); return -1 }
            override fun read(bytes: ByteArray, off: Int, len: Int): Int = read()
        })
        val directory = File(context.cacheDir, "native_tmp")
        val before = directory.listFiles()?.map { it.name }?.toSet().orEmpty()
        val job = launch(Dispatchers.Default) { stageImageBatch(context, listOf(first, second), listOf(existing)).forEach { it.close() } }
        try {
            assertTrue(started.await(5, TimeUnit.SECONDS))
            job.cancel(); release.countDown(); job.join()
            assertTrue(existing.file.exists())
            assertEquals(before, directory.listFiles()?.map { it.name }?.toSet().orEmpty())
        } finally { release.countDown(); job.cancelAndJoin(); existing.close() }
    }
}
