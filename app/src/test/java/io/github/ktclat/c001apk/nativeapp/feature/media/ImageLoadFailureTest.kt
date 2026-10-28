// SPDX-License-Identifier: AGPL-3.0-only
package io.github.ktclat.c001apk.nativeapp.feature.media

import coil.network.HttpException
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import org.junit.Assert.*
import org.junit.Test
import java.io.EOFException
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLHandshakeException

class ImageLoadFailureTest {
    @Test fun statusAndSpecificTransportClassesWinWithoutReadingMessages() {
        val response = Response.Builder().request(Request.Builder().url("https://example.test/private?token=SECRET").build())
            .protocol(Protocol.HTTP_1_1).code(567).message("SECRET").build()
        val http = imageLoadFailure(IllegalStateException("SECRET", HttpException(response)), true)
        assertEquals(ImageLoadFailure(ImageLoadFailureKind.HTTP, 567), http)
        assertEquals(ImageLoadFailureKind.DNS, imageLoadFailure(UnknownHostException("SECRET"), true).kind)
        assertEquals(ImageLoadFailureKind.TIMEOUT, imageLoadFailure(SocketTimeoutException("SECRET"), true).kind)
        assertEquals(ImageLoadFailureKind.TLS, imageLoadFailure(SSLHandshakeException("SECRET"), true).kind)
        assertFalse(http.displayText().contains("SECRET"))
    }
    @Test fun pipelinePhaseDistinguishesDecodeIoFromFetchIoAndUnknownUiIo() {
        assertEquals(ImageLoadFailureKind.DECODE, imageLoadFailure(EOFException("read failed"), true).kind)
        assertEquals(ImageLoadFailureKind.NETWORK, imageLoadFailure(IOException("decode failed"), false).kind)
        assertEquals(ImageLoadFailureKind.READ_OR_DECODE, imageLoadFailure(IOException("SECRET")).kind)
        assertEquals(ImageLoadFailureKind.UNKNOWN, imageLoadFailure(IllegalArgumentException("decode failed")).kind)
    }
    @Test fun cyclicCauseChainIsBoundedAndCannotLeakText() {
        val error = object : RuntimeException("SECRET") { override val cause: Throwable get() = this }
        assertEquals(ImageLoadFailure(ImageLoadFailureKind.UNKNOWN), imageLoadFailure(error))
    }
    @Test fun exportedStatusOnlyAllowsHttpRangeAndMatchingKind() {
        for (status in listOf(0, 99, 600, Int.MAX_VALUE)) {
            assertTrue(runCatching { ImageLoadFailure(ImageLoadFailureKind.HTTP, status) }.isFailure)
        }
        assertTrue(runCatching { ImageLoadFailure(ImageLoadFailureKind.DNS, 200) }.isFailure)
        assertTrue(runCatching { ImageLoadFailure(ImageLoadFailureKind.HTTP) }.isFailure)
    }
}
