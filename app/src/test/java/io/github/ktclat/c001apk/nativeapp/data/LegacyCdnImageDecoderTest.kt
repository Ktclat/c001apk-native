// SPDX-License-Identifier: AGPL-3.0-only
package io.github.ktclat.c001apk.nativeapp.data

import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

/** Wire URLs use HTTP even though these public CDN origins support HTTPS. */
class LegacyCdnImageDecoderTest {
    @Test fun feedPicturesAvatarAndEmbeddedLinkSurviveLegacyCdnUrls() {
        val item = ApiDecoder.item(Json.parseToJsonElement("""{
          "id":"1","entityType":"feed",
          "userAvatar":"http://avatar.coolapk.com/data/000/53/63/81_avatar_middle.jpg?1456135138",
          "picArr":["http://image.coolapk.com/feed/example@1080x2160.jpeg"],
          "extra_url":"/feed/2","extra_pic":"//image.coolapk.com/image/link.png"
        }"""))
        assertEquals("https://avatar.coolapk.com/data/000/53/63/81_avatar_middle.jpg?1456135138", item.avatarUrl)
        assertEquals(listOf("https://image.coolapk.com/feed/example@1080x2160.jpeg"), item.imageUrls)
        assertEquals("https://image.coolapk.com/image/link.png", item.feed?.link?.image)
    }

    @Test fun homeEntryAppLogoAndProfileAvatarAreUpgradedBeforeRendering() {
        val card = ApiDecoder.item(Json.parseToJsonElement("""{
          "id":"1","entityType":"card","entityTemplate":"iconLinkGridCard",
          "entities":[{"id":"2","entityType":"page","title":"入口","url":"/feed/2",
            "logo":"http://image.coolapk.com/image/icon.png"}]
        }"""))
        assertEquals(listOf("https://image.coolapk.com/image/icon.png"), card.children.single().imageUrls)
        assertEquals("https://image.coolapk.com/image/icon.png", card.children.single().cardFields?.logo)
        val app = ApiDecoder.item(Json.parseToJsonElement("""{"id":"3","entityType":"apk","logo":"http://image.coolapk.com/apk/logo.png"}"""))
        assertEquals("https://image.coolapk.com/apk/logo.png", app.entityCard?.image)
        val own = decodeOwnProfile(Json.parseToJsonElement("""{"uid":"1","userAvatar":"http://avatar.coolapk.com/a.jpg"}"""), "1")
        assertEquals("https://avatar.coolapk.com/a.jpg", own.avatarUrl)
    }

    @Test fun unrelatedCleartextAndMalformedImageUrlsRemainUnavailable() {
        for (url in listOf("http://other.example/a.png", "http://image.coolapk.com.evil.test/a.png",
            "http://image.coolapk.com:81/a.png", "https://user@image.coolapk.com/a.png",
            "file:///private.png", "data:image/png;base64,AAAA", "//other.example/a.png")) {
            val item = ApiDecoder.item(Json.parseToJsonElement("""{"entityType":"feed","userAvatar":"$url","picArr":["$url"]}"""))
            assertNull(url, item.avatarUrl)
            assertTrue(url, item.imageUrls.isEmpty())
        }
    }
}
