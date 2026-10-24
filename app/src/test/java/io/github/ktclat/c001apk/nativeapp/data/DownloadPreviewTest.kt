// SPDX-License-Identifier: AGPL-3.0-only
package io.github.ktclat.c001apk.nativeapp.data

import io.github.ktclat.c001apk.nativeapp.domain.*
import kotlinx.coroutines.runBlocking
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test

class DownloadPreviewTest {
    private fun transport(location: String, seen: MutableList<Request>): CoolapkHttpTransport = CoolapkHttpTransport(
        client = OkHttpClient.Builder().addInterceptor { chain ->
            seen += chain.request()
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(302).message("Found")
                .header("Location",location).body("".toResponseBody()).build()
        }.build(), protocol = ClientProtocol("synthetic-download-fixture", {1770000000}))
    @Test fun downloadOnlyReturnsPreviewWithoutFollowingOrSendingToCdn() = runBlocking {
        val seen = mutableListOf<Request>()
        val result = transport("https://downloads.example.org/file.apk?signature=fixture",seen).getApkDownloadLocation("com.example.app","42",123L) as ApiResult.Success
        assertEquals("https://downloads.example.org/file.apk?signature=fixture",result.value)
        assertEquals(1,seen.size)
        val url = seen.single().url
        assertEquals("api.coolapk.com",url.host)
        assertEquals("/v6/apk/download",url.encodedPath)
        assertEquals("com.example.app",url.queryParameter("pn"))
        assertEquals("42",url.queryParameter("aid"))
        assertEquals("123",url.queryParameter("vc"))
        assertEquals("",url.queryParameter("extra"))
        assertNull(seen.single().header("Cookie"))
    }
    @Test fun unsafeLocationsAndMalformedArgumentsAreRejected() = runBlocking {
        for (location in listOf("http://downloads.example.org/a", "https://user:pass@downloads.example.org/a", "https://127.0.0.1/a", "https://[::1]/a", "https://192.168.1.1/a", "https://localhost/a", "https://host.local/a", "https://host.internal/a", "https://host.test/a", "https://downloads.example.org:8443/a", "file:///a", "https://downloads.example.org/a#fragment")) {
            val seen=mutableListOf<Request>()
            assertTrue(location,transport(location,seen).getApkDownloadLocation("com.example.app","42",1) is ApiResult.Failure)
            assertEquals(1,seen.size)
        }
        val seen=mutableListOf<Request>()
        assertTrue(transport("https://downloads.example.org/a",seen).getApkDownloadLocation("bad/package","42",1) is ApiResult.Failure)
        assertEquals(0,seen.size)
    }
}
