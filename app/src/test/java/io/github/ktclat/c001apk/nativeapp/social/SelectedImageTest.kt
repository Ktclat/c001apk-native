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
import org.robolectric.annotation.Config
import org.robolectric.Shadows.shadowOf
import java.io.File
import java.io.InputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SelectedImageTest {
    @Test fun cancellationRemovesStagingFileEvenBeforeResultDelivery() = runBlocking {
        val context = RuntimeEnvironment.getApplication() as Context
        val uri = Uri.parse("content://synthetic/image")
        val started = CountDownLatch(1); val release = CountDownLatch(1)
        shadowOf(context.contentResolver).registerInputStream(uri, object : InputStream() {
            override fun read(): Int { started.countDown(); release.await(5, TimeUnit.SECONDS); return 1 }
            override fun read(bytes: ByteArray, off: Int, len: Int): Int { started.countDown(); release.await(5, TimeUnit.SECONDS); bytes[off] = 1; return 1 }
        })
        val directory = File(context.cacheDir, "native_tmp")
        val before = directory.listFiles()?.map { it.name }?.toSet().orEmpty()
        val job = launch(Dispatchers.Default) { stageSelectedImage(context, uri).close() }
        assertTrue(started.await(5, TimeUnit.SECONDS))
        job.cancel(); release.countDown(); job.join()
        assertEquals(before, directory.listFiles()?.map { it.name }?.toSet().orEmpty())
    }
    @Test fun rejectsNonContentUriWithoutReadingFiles() = runBlocking {
        try { stageSelectedImage(RuntimeEnvironment.getApplication(), Uri.parse("file:///data/local/private")); fail("must reject file URI") }
        catch (_: IllegalArgumentException) { }
    }
}
