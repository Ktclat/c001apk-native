// SPDX-License-Identifier: AGPL-3.0-only
package io.github.ktclat.c001apk.nativeapp.data

import org.junit.Assert.*
import org.junit.Test

class PublicImageUrlTest {
    @Test fun upgradesOnlyKnownPublicCdnOriginsAndPreservesPathAndQuery() {
        for (host in listOf("image.coolapk.com", "avatar.coolapk.com", "static.coolapk.com")) {
            val suffix = "/a%20b@1080x2400.jpeg?version=1456135138&x=a%2Fb"
            val expected = "https://$host$suffix"
            assertEquals(expected, publicImageUrl("http://$host$suffix"))
            assertEquals(expected, publicImageUrl("http://$host:80$suffix"))
            assertEquals(expected, publicImageUrl("//$host$suffix"))
            assertEquals(expected, publicImageUrl(expected))
        }
        assertEquals("https://image.coolapk.com/a", publicImageUrl("HTTP://IMAGE.COOLAPK.COM/a"))
        assertEquals("https://existing.example/a", publicImageUrl("https://existing.example/a"))
    }

    @Test fun rejectsAmbiguousOrCredentialedUrlsWithoutCleartextFallback() {
        for (url in listOf("", " http://image.coolapk.com/a", "http://image.coolapk.com/a ",
            "https://image.coolapk.com/a\n", "http://image.coolapk.com:443/a", "https://image.coolapk.com:80/a",
            "http://image.coolapk.com.evil.test/a", "http://sub.image.coolapk.com/a", "http://api.coolapk.com/a",
            "https://image.coolapk.com/a#fragment", "https://image.coolapk.com/a#", "https://@image.coolapk.com/a",
            "https://user:pass@image.coolapk.com/a", "https://image.coolapk.com\\@evil.test/a",
            "//evil.test/a", "file:///a", "content://a", "data:image/png;base64,AAA", "https://", "https://image.coolapk.com/%ZZ")) {
            assertNull(url, publicImageUrl(url))
        }
        assertNull(publicImageUrl("https://image.coolapk.com/" + "a".repeat(12_000)))
    }

    @Test fun eventPictureUsesTheSameUpgradeWithoutChangingActionRouting() {
        val event = decodeEventDetail(kotlinx.serialization.json.Json.parseToJsonElement(
            """{"id":"7","title":"活动","pic":"http://image.coolapk.com/event.jpg"}"""), "7")
        assertEquals(listOf("https://image.coolapk.com/event.jpg"), event.imageUrls)
    }
}
