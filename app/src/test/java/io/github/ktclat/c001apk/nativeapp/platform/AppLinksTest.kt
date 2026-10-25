// SPDX-License-Identifier: AGPL-3.0-only
package io.github.ktclat.c001apk.nativeapp.platform

import org.junit.Assert.*
import org.junit.Test

/** Pure routing fixtures: no Android intent dispatch, provider calls or account data. */
class AppLinksTest {
    private val hosts = listOf("coolapk.com", "www.coolapk.com", "m.coolapk.com", "coolapk1s.com", "www.coolapk1s.com")

    @Test fun officialAliasesRouteAllExistingDestinations() {
        val routes = mapOf("feed/123" to "feed:123", "event/4" to "event:4", "u/name" to "user:name",
            "apk/com.example" to "app:com.example", "game/com.example" to "app:com.example",
            "t/topic" to "topic:topic", "product/phone" to "product:phone", "dyh/5" to "dyh:5", "collection/2001" to "collection:2001")
        for (host in hosts) for (scheme in listOf("https", "http")) for ((path, route) in routes) {
            val url = "$scheme://$host/$path?s=share#reply"
            assertEquals(url, AppLink.Internal(route), parseAppLink(url))
        }
        assertEquals(AppLink.Internal("feed:123"), parseAppLink("https://M.COOLAPK.COM/feed/123/"))
    }

    @Test fun customAuthoritiesAndExistingShorthandRouteWithoutMetadata() {
        for (host in hosts + "com.coolapk.market") {
            val url = "coolmarket://$host/feed/123?s=share#reply"
            assertEquals(url, AppLink.Internal("feed:123"), parseAppLink(url))
        }
        for ((url, route) in mapOf("coolmarket://feed/123" to "feed:123", "coolmarket://event/4" to "event:4",
            "coolmarket://u/name" to "user:name", "coolmarket://product/phone" to "product:phone",
            "coolmarket://com.coolapk.market/t/topic?type=8" to "coolpic:topic")) {
            assertEquals(url, AppLink.Internal(route), parseAppLink(url))
        }
    }

    @Test fun unicodePlusAndTopicQueryAreDecodedOnce() {
        for (prefix in listOf("", "https://m.coolapk.com", "coolmarket://com.coolapk.market")) {
            assertEquals(AppLink.Internal("topic:摄影 & 日常"), parseAppLink("$prefix/t/%E6%91%84%E5%BD%B1%20%26%20%E6%97%A5%E5%B8%B8?s=x#reply"))
            assertEquals(AppLink.Internal("topic:a+b+c"), parseAppLink("$prefix/t/a+b%2Bc"))
            assertEquals(AppLink.Internal("topic:%2F"), parseAppLink("$prefix/t/%252F"))
            assertEquals(AppLink.Internal("coolpic:摄影"), parseAppLink("$prefix/t/摄影?type=%38#ignored"))
            assertEquals(AppLink.Internal("topic:摄影"), parseAppLink("$prefix/t/摄影?type=8+"))
            assertEquals(AppLink.Internal("topic:😀�"), parseAppLink("$prefix/t/%F0%9F%98%80%EF%BF%BD"))
        }
        assertEquals(AppLink.Internal("page:/page?url=%2Ffeed%2F123&title=a+b"), parseAppLink("/page?url=%2Ffeed%2F123&title=a+b"))
    }

    @Test fun lookalikesAndExplicitPortsStayExternalNotInternal() {
        for (host in listOf("coolapk.com.evil.test", "evilcoolapk.com", "m.coolapk.com.evil.test", "www.coolapk1s.com.evil.test", "coolapk1s.com.", "evil.test")) {
            val url = "https://$host/feed/123"
            assertEquals(url, AppLink.External(url), parseAppLink(url))
        }
        for (host in hosts) for (port in listOf(80, 443, 8443)) {
            val url = "https://$host:$port/feed/123"
            assertEquals(url, AppLink.External(url), parseAppLink(url))
        }
    }

    @Test fun userInfoNeverRoutesOrOpensExternally() {
        for (scheme in listOf("https", "http", "coolmarket")) for (host in hosts + "com.coolapk.market") {
            for (info in listOf("name", "name:password", "")) {
                val url = "$scheme://$info@$host/feed/123"
                assertEquals(url, AppLink.Unsupported, parseAppLink(url))
            }
        }
        assertEquals(AppLink.Unsupported, parseAppLink("https://coolapk.com@evil.test/feed/123"))
    }

    @Test fun unknownOrMalformedCustomAuthoritiesAreUnsupported() {
        for (url in listOf("coolmarket://com.coolapk.market.evil/feed/123", "coolmarket://evil.test/feed/123",
            "coolmarket://com.coolapk.market:443/feed/123", "coolmarket://feed:80/123",
            "coolmarket://com.coolapk.market", "coolmarket://com.coolapk.market/feed",
            "coolmarket://com.coolapk.market/unknown/123")) {
            assertEquals(url, AppLink.Unsupported, parseAppLink(url))
        }
    }

    @Test fun missingIdsAndExtraSegmentsNeverBecomeInternalRoutes() {
        for (path in listOf("feed", "feed/", "feed/not-a-number", "feed/1/2", "event/", "event/no", "u/", "apk/", "game/", "t/", "t/%20", "product/", "dyh/no", "page")) {
            assertEquals(path, AppLink.Unsupported, parseAppLink("/$path"))
            assertEquals(path, AppLink.Unsupported, parseAppLink("coolmarket://com.coolapk.market/$path"))
            val external = "https://m.coolapk.com/$path"
            assertEquals(path, AppLink.External(external), parseAppLink(external))
        }
    }

    @Test fun encodedSeparatorsNeverBecomeInternalIds() {
        for (kind in listOf("feed", "event", "u", "apk", "game", "t", "product", "dyh", "collection")) {
            for (id in listOf("1%2F2", "1%2f2", "1%5C2", "1%5c2")) {
                assertEquals("$kind/$id", AppLink.Unsupported, parseAppLink("/$kind/$id"))
                assertEquals("$kind/$id", AppLink.Unsupported, parseAppLink("coolmarket://com.coolapk.market/$kind/$id"))
                val external = "https://m.coolapk.com/$kind/$id"
                assertEquals(external, AppLink.External(external), parseAppLink(external))
            }
        }
    }

    @Test fun malformedEscapesAndInvalidUtf8FailClosed() {
        for (value in listOf("%", "%2", "%GG", "%FF", "%C0%AF", "%E0%80%AF", "%ED%A0%80", "%F4%90%80%80", "%E6%B5", "%80")) {
            for (prefix in listOf("", "https://m.coolapk.com", "coolmarket://com.coolapk.market")) {
                for (path in listOf("/t/$value", "/feed/123?type=$value")) {
                    assertEquals(prefix + path, AppLink.Unsupported, parseAppLink(prefix + path))
                }
            }
        }
    }

    @Test fun unsupportedSchemesAndProtocolRelativeLinksRemainBlocked() {
        for (url in listOf("//m.coolapk.com/feed/123", "javascript:alert(1)", "file:///feed/123", "data:text/plain,test", "ftp://coolapk.com/feed/123")) {
            assertEquals(url, AppLink.Unsupported, parseAppLink(url))
        }
    }
}
