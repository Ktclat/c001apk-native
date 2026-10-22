// SPDX-License-Identifier: AGPL-3.0-only
package io.github.ktclat.c001apk.nativeapp.feature.media

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import org.junit.Assert.*
import org.junit.Test

class MediaTransferTest {
    @Test fun boundsTheStreamEvenWithoutContentLength() {
        val output = ByteArrayOutputStream()
        assertThrows(IllegalStateException::class.java) { copyBoundedImage(ByteArrayInputStream(ByteArray(20_000)), output, 9000) }
        assertTrue(output.size() <= 9000)
    }
    @Test fun exactLimitAndGifBytesRemainUnmodified() {
        val bytes = "GIF89a".toByteArray()
        val output = ByteArrayOutputStream()
        assertEquals(6L, copyBoundedImage(ByteArrayInputStream(bytes), output, 6))
        assertArrayEquals(bytes, output.toByteArray())
    }
}
