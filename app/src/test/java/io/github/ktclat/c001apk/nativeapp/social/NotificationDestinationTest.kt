// SPDX-License-Identifier: AGPL-3.0-only
package io.github.ktclat.c001apk.nativeapp.social

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class NotificationDestinationTest {
    private fun decode(raw: String) = decodeNotificationContent(Json.parseToJsonElement(raw) as JsonObject)
    @Test fun rootFeedIdNeverBecomesCommentRid() {
        for(url in listOf("/feed/303?rid=202", "https://www.coolapk.com/feed/303?rid=202&shareUid=1", "coolmarket://feed/303", "coolmarket://com.coolapk.market/feed/303"))
            assertEquals(NotificationDestination.Feed("303"), notificationLink(url))
        assertEquals(NotificationDestination.Feed("303"), decode("""{"id":202,"entityType":"feed_reply","url":"/feed/303?rid=202"}""").destination)
        assertEquals(NotificationTargetIssue.MISSING, (decode("""{"id":202,"entityType":"feed_reply","feed":{"id":303}}""").destination as NotificationDestination.Unavailable).reason)
    }
    @Test fun exactInternalAuthoritiesOnlyAndNoQueryIdInjection() {
        listOf("https://coolapk.com.evil/feed/1", "https://user@coolapk.com/feed/1", "https://coolapk.com:443/feed/1", "//coolapk.com/feed/1", "javascript:alert(1)", "intent://feed/1", "file:///feed/1", "/feed/1/2", "/feed/%31", "/feed/1?rid=2&id=3", "/feed/1?rid=2&rid=3", "/feed/1?rid=bad", "/feed/0", "/feed/-1", "/feed/1#%00", "/u/alice", "/feed/1\n", " /feed/1", "/feed/1?redirect=https://evil.invalid").forEach { raw ->
            assertTrue(raw, notificationLink(raw) is NotificationDestination.Unavailable)
        }
    }
    @Test fun firstAnchorIsAuthoritativeEvenIfEmptyUnsupportedOrDuplicated() {
        for(first in listOf("https://evil.invalid/feed/1", "", "javascript:alert(1)"))
            assertTrue(firstNotificationLink("<a href='$first'>first</a><a href='/feed/2'>later</a>") is NotificationDestination.Unavailable)
        assertTrue(firstNotificationLink("<a href='/feed/1' href='/feed/2'>ambiguous</a>") is NotificationDestination.Unavailable)
        assertTrue(firstNotificationLink("<a href>empty</a><a href='/feed/2'>later</a>") is NotificationDestination.Unavailable)
        assertEquals(NotificationDestination.Feed("2"), firstNotificationLink("<a title=\"href='/feed/1'\" href='/feed/2'>actual</a>"))
        assertEquals(NotificationDestination.Feed("3"), firstNotificationLink("<a name='marker'>not link</a><a class='x' href='/feed/3?rid=4&amp;shareUid=1'>body</a>"))
        assertTrue(firstNotificationLink("x".repeat(12_001) + "<a href='/feed/1'>x</a>") is NotificationDestination.Unavailable)
    }
    @Test fun roleSpecificActorsCannotBecomeRecipientOrCommentAuthor() {
        val follower = decode("""{"entityType":"notification","id":80,"fromuid":11,"uid":1,"fromusername":"新关注者","type":"contacts_follow","url":"/u/11","note":"关注了你"}""")
        assertEquals("11", follower.actorUid); assertEquals("新关注者",follower.actorName)
        assertEquals(NotificationDestination.User("11"),follower.destination); assertEquals("关注了你",follower.text)
        val like = decode("""{"entityType":"feed_reply","id":606,"uid":1,"username":"被赞作者","likeUserInfo":{"uid":10,"username":"点赞者"},"url":"/feed/707?rid=606","infoHtml":"评论","message":"<b>评论节选</b>"}""")
        assertEquals("10",like.actorUid); assertEquals("点赞者",like.actorName); assertEquals("评论节选",like.text)
        assertEquals("赞了你的评论",like.context); assertEquals(NotificationDestination.Feed("707"),like.destination)
        assertNull(decode("""{"entityType":"feed_reply","uid":1,"likeUserInfo":{"uid":"invalid"}}""").actorUid)
    }
    @Test fun malformedExplicitActorDoesNotBecomeRecipientOrOriginalAuthor() {
        for (bad in listOf("\"\"", "\"invalid\"", "[]", "{}")) {
            assertNull(decode("""{"entityType":"notification","uid":1,"fromuid":$bad}""").actorUid)
            assertNull(decode("""{"entityType":"feed_reply","uid":1,"likeUserInfo":$bad}""").actorUid)
        }
        assertEquals("1",decode("""{"entityType":"notification","uid":1,"fromuid":null}""").actorUid)
        assertEquals("2",decode("""{"entityType":"contacts","id":90,"userInfo":{"uid":2},"fUserInfo":{"uid":3,"username":"fallback name"}}""").actorUid)
        assertNull(decode("""{"entityType":"contacts","id":90,"userInfo":{"uid":""},"fUserInfo":{"uid":3}}""").actorUid)
    }
    @Test fun quotedAttributeAndInertMarkupNeverBecomeAnotherDestination() {
        assertEquals(NotificationDestination.Feed("2"),firstNotificationLink("<a title=\"href='/feed/999'\">not link</a><a href='/feed/2'>real</a>"))
        for (note in listOf("<!-- <a href='/feed/999'> -->", "<script>\"<a href='/feed/999'>\"</script>", "<style>a[href='/feed/999']{}</style>"))
            assertEquals(NotificationDestination.Feed("2"),firstNotificationLink(note + "<a href='/feed/2'>real</a>"))
    }
    @Test fun unicodeSpacingAndTreeRepairMarkupCannotInventAnAnchor() {
        for (note in listOf("<a href\u00a0='/feed/999'>fake</a>", "<a\u00a0href='/feed/999'>fake</a>",
            "<select><a href='/feed/999'>ignored</a></select>", "<template><a href='/feed/999'>inert</a></template>",
            "<table><a href='/feed/999'>reparented</a></table>", "<svg><a href='/feed/999'>foreign</a></svg>"))
            assertTrue(firstNotificationLink(note + "<a href='/feed/2'>real</a>") is NotificationDestination.Unavailable)
        assertEquals(NotificationDestination.Feed("2"),firstNotificationLink("<a\t href\n=\r'/feed/2'>real</a>"))
    }
    @Test fun typedRoleDoesNotPermitMismatchedDestinations() {
        assertTrue(decode("""{"entityType":"feed_reply","id":1,"url":"/u/9"}""").destination is NotificationDestination.Unavailable)
        assertTrue(decode("""{"entityType":"notification","type":"contacts_follow","url":"/feed/9"}""").destination is NotificationDestination.Unavailable)
        assertEquals(NotificationDestination.User("4"),decode("""{"entityType":"contacts","id":900,"fUserInfo":{"uid":4,"username":"联系人"}}""").destination)
    }
    @Test fun noteTextAndLengthsAreBoundedWithoutExecutingMarkup() {
        val content = decode("""{"entityType":"notification","fromuid":7,"note":"你好 <a href='/feed/8'>动态</a> &amp; <img src='https://evil.invalid/x'>"}""")
        assertTrue(content.text.contains("你好 动态 &")); assertEquals(NotificationDestination.Feed("8"),content.destination)
        assertTrue(decode(buildJsonObject { put("entityType","feed"); put("id",1); put("message","x".repeat(20_000)) }.toString()).truncated)
    }
}
