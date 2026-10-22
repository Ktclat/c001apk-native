// SPDX-License-Identifier: AGPL-3.0-only
package io.github.ktclat.c001apk.nativeapp.data

import io.github.ktclat.c001apk.nativeapp.domain.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

/** Local synthetic fixtures, without transport, credentials, or network. */
class FeedMetadataDecoderTest {
    private fun decode(body: String) = ApiDecoder.item(Json.parseToJsonElement(body))
    @Test fun sourceSnakeCaseMetadataAndLongPreviewAreExplicit() {
        val item = ApiDecoder.item(buildJsonObject {
            put("entityType", "feed"); put("device_title", "Android device"); put("ip_location", "北京")
            put("message", "x".repeat(12_001))
        })
        assertEquals("Android device", item.feed?.deviceTitle)
        assertEquals("北京", item.feed?.ipLocation)
        assertEquals(12_000, item.message.length)
        assertTrue(item.bodyTruncated)
    }
    @Test fun richFeedPreservesTypedRelationshipsAndActualTitle() {
        val body = requireNotNull(javaClass.getResource("/rich-feed.json")).readText()
        val item = decode(body)
        assertEquals("Actual title", item.title)
        assertEquals("Author", item.userName)
        assertEquals("7", item.userId)
        assertEquals(listOf("https://example.invalid/photo"), item.imageUrls)
        assertTrue(item.children.isEmpty())
        assertEquals("103", item.replies.single().id)
        val feed = requireNotNull(item.feed)
        assertEquals("Device", feed.deviceTitle)
        assertEquals("<b>Info</b>", feed.infoHtml)
        assertEquals("Location", feed.ipLocation)
        assertEquals(true, feed.pinned)
        assertEquals(ExplicitLikeState.NOT_LIKED, feed.likeState)
        assertEquals(245, feed.likeCount)
        assertEquals(0, feed.replyCount)
        assertEquals("102", feed.forward?.id)
        assertEquals(FeedReferenceKind.FORWARD, feed.forward?.kind)
        assertEquals("8", feed.forward?.authorId)
        assertEquals("103", feed.hotReply?.id)
        assertEquals("101", feed.hotReply?.parentFeedId)
        assertEquals(FeedReferenceKind.HOT_REPLY, feed.hotReply?.kind)
        assertEquals("Reply Author", feed.hotReply?.author)
        assertEquals("/page?url=topic", feed.link?.url)
        assertEquals("https://example.invalid/link", feed.link?.image)
        assertEquals("product", feed.target?.entityType)
        assertEquals("product", feed.target?.targetType)
        assertEquals("/product/11", feed.target?.url)
        assertEquals("topic", feed.relations.single().entityType)
    }
    @Test fun missingCountsPinAndActionStayUnknown() {
        val feed = requireNotNull(decode("""{"entityType":"feed","likenum":900}""").feed)
        assertEquals(ExplicitLikeState.UNKNOWN, feed.likeState)
        assertNull(feed.replyCount)
        assertNull(feed.pinned)
        assertNull(feed.forward)
        assertNull(feed.hotReply)
        assertEquals("", decode("""{"entityType":"feed","title":"wrong","message":"not a title"}""").title)
        assertEquals(ExplicitLikeState.UNKNOWN, decode("""{"entityType":"feed","userAction":{"like":2}}""").feed?.likeState)
        assertEquals(ExplicitLikeState.LIKED, decode("""{"entityType":"feed","likenum":0,"userAction":{"like":1}}""").feed?.likeState)
    }
    @Test fun absentForwardWithSourceMarkerIsDeletedNotClickableIdentity() {
        val forward = requireNotNull(decode("""{"entityType":"feed","id":1,"forwardSourceType":"feed"}""").feed?.forward)
        assertTrue(forward.deleted)
        assertEquals("", forward.id)
        assertEquals("1", forward.parentFeedId)
    }
    @Test fun containerEntitiesStayOwnedAndRepliesNeverBecomePeerPosts() {
        val item = decode("""{"entityType":"card","entityTemplate":"iconMiniScrollCard","entities":[{"id":2,"entityType":"feed","replyRows":[{"id":3,"message":"nested"}]}],"rows":[{"id":4}]}""")
        assertEquals("iconMiniScrollCard", item.entityTemplate)
        assertEquals(1, item.children.size)
        assertEquals("2", item.children.single().id)
        assertTrue(item.children.single().children.isEmpty())
        assertEquals("3", item.children.single().replies.single().id)
    }
    @Test fun userIdsAreExplicitAndAvatarDoesNotBecomePostMedia() {
        val user = decode("""{"id":"card-id","entityType":"user","userInfo":{"uid":42,"username":"Person"},"userAvatar":"https://example.invalid/avatar","url":"/u/42"}""")
        assertEquals("card-id", user.id)
        assertEquals("42", user.userId)
        assertEquals("Person", user.userName)
        assertTrue(user.imageUrls.isEmpty())
        assertEquals("/u/42", user.targetUrl)
        assertEquals("", decode("""{"id":42,"entityType":"user"}""").userId)
        val contact = decode("""{"entityType":"contacts","fUserInfo":{"uid":8,"username":"Friend","userAvatar":"https://example.invalid/friend"}}""")
        assertEquals("8", contact.userId)
        assertEquals("Friend", contact.userName)
    }
    @Test fun countAndTextBoundsAreExplicit() {
        val item = decode(buildJsonObject {
            put("entityType", "feed"); put("message", "a".repeat(20_000))
            put("likenum", Long.MAX_VALUE); put("replynum", -1)
            put("picArr", JsonArray(List(30) { JsonPrimitive("https://example.invalid/$it") }))
        }.toString())
        assertEquals(12_000, item.message.length)
        assertEquals(18, item.imageUrls.size)
        assertEquals(Int.MAX_VALUE, item.feed?.likeCount)
        assertNull(item.feed?.replyCount)
    }
    @Test fun recursiveReplyTreesStopAtDepthThree() {
        fun node(depth: Int): JsonObject = buildJsonObject {
            put("id", depth); put("entityType", "feed_reply")
            if (depth < 12) put("replyRows", JsonArray(listOf(node(depth + 1))))
        }
        val item = ApiDecoder.item(node(0))
        assertTrue(item.replies.single().replies.single().replies.single().replies.isEmpty())
    }
}
