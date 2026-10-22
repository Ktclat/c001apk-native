// SPDX-License-Identifier: AGPL-3.0-only
package io.github.ktclat.c001apk.nativeapp.social

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Pure synthetic contract tests: no network, tokens, account or external writes. */
class OssImageSafetyTest {
    private val now = parseOssExpiration("2026-10-22T07:00:00Z")!!
    private fun image() = SelectedImage(File.createTempFile("fake-image-", ".jpg").apply { writeBytes(byteArrayOf(1, 2, 3)) },
        "image/jpeg", 1, 1, "5289df737df57326fcdd22597afb1fac", "Uonfc331cyb83SJZevsfrA==", "synthetic.jpg")
    private fun grant(endpoint: String = "oss-cn-hangzhou.aliyuncs.com", path: String = "message/2026/synthetic.jpg", expiry: String = "2026-10-22T08:00:00Z", md5: String = "5289df737df57326fcdd22597afb1fac"): JsonElement = buildJsonObject {
        put("uploadPrepareInfo", buildJsonObject {
            put("endPoint", endpoint); put("bucket", "synthetic-bucket"); put("expiration", expiry)
            put("accessKeyId", "SYNTHETIC-ID"); put("accessKeySecret", "SYNTHETIC-SECRET"); put("securityToken", "SYNTHETIC-TOKEN")
        })
        put("fileInfo", buildJsonArray { add(buildJsonObject {
            put("name", "synthetic.jpg"); put("md5", md5); put("uploadFileName", path)
        }) })
    }
    @Test fun permitsOnlyExactOssEndpointAndIssuedObject() {
        image().use { image ->
            val parsed = parseOssGrant(grant(), image, now)!!
            assertEquals("synthetic-bucket.oss-cn-hangzhou.aliyuncs.com", parsed.destination.host)
            assertEquals("/message/2026/synthetic.jpg", parsed.destination.encodedPath)
            assertTrue(parsed.destination.isHttps)
            for (host in listOf("evil.example", "oss-cn-hangzhou.aliyuncs.com.evil", "http://oss-cn-hangzhou.aliyuncs.com", "oss-cn-hangzhou.aliyuncs.com:8443", "127.0.0.1")) {
                assertNull(parseOssGrant(grant(endpoint = host), image, now))
            }
        }
    }
    @Test fun rejectsTraversalOtherPrefixesAndChangedContent() {
        image().use { image ->
            for (path in listOf("feed/a.jpg", "message/../a.jpg", "message/%2e%2e/a.jpg", "message//a.jpg", "message/a.jpg?token=x"))
                assertNull(parseOssGrant(grant(path = path), image, now))
            assertNull(parseOssGrant(grant(md5 = "wrong"), image, now))
        }
    }
    @Test fun expiryIsMandatoryBoundedAndUtcStrict() {
        image().use { image ->
            assertNull(parseOssGrant(grant(expiry = ""), image, now))
            assertNull(parseOssGrant(grant(expiry = "2026-10-22T06:59:59Z"), image, now))
            assertNull(parseOssGrant(grant(expiry = "2027-10-22T08:00:00Z"), image, now))
            assertNull(parseOssExpiration("2026-13-22T07:00:00Z"))
            assertNull(parseOssExpiration("2026-10-22T07:00:00Ztrailing"))
        }
    }
    @Test fun headersUseOnlyTemporaryOssCredentialsAndFixedCallback() {
        image().use { image ->
            val parsed = parseOssGrant(grant(), image, now)!!
            assertFalse(parsed.toString().contains("SYNTHETIC"))
            val headers = ossHeaders(parsed, image, now)
            assertTrue(headers.getValue("Authorization").startsWith("OSS SYNTHETIC-ID:"))
            assertFalse(headers.containsKey("Cookie")); assertFalse(headers.containsKey("X-App-Token"))
            assertEquals("Thu, 22 Oct 2026 07:00:00 GMT", headers["Date"])
            assertEquals(image.md5Base64, headers["Content-MD5"])
        }
    }
    @Test fun messageImagesCannotUseArbitraryOrigins() {
        assertNotNull(safeMessageImage("https://image.coolapk.com/message/a.jpg"))
        for (url in listOf("http://image.coolapk.com/a", "https://image.coolapk.com.evil/a", "https://127.0.0.1/a", "https://image.coolapk.com:444/a", "https://user@image.coolapk.com/a"))
            assertNull(safeMessageImage(url))
    }
}
