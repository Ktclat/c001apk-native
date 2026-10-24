// SPDX-License-Identifier: AGPL-3.0-only
package io.github.ktclat.c001apk.nativeapp.local

import io.github.ktclat.c001apk.nativeapp.settings.CacheCleaner
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class CacheSafetyTest {
    @Test fun onlyDisposableCacheFilesAreRemoved() = runTest {
        val root=Files.createTempDirectory("cache-safety").toFile()
        try {
            val cache=File(root,"cache"); val image=File(cache,"image_cache").apply { mkdirs() }
            File(image,"image").writeText("fake image")
            val settings=File(root,"files/settings").apply { parentFile.mkdirs(); writeText("synthetic") }
            val db=File(root,"databases/library").apply { parentFile.mkdirs(); writeText("synthetic") }
            val unknown=File(cache,"unknown").apply { writeText("preserve") }
            Files.createSymbolicLink(File(image,"outside").toPath(),settings.toPath())
            val result=CacheCleaner(cache).clear()
            assertEquals(10,result.removedBytes); assertTrue(settings.exists()); assertTrue(db.exists()); assertTrue(unknown.exists())
            assertFalse(File(image,"image").exists())
        } finally { root.deleteRecursively() }
    }
}
