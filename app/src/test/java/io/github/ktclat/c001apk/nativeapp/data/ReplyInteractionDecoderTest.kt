// SPDX-License-Identifier: AGPL-3.0-only
package io.github.ktclat.c001apk.nativeapp.data

import io.github.ktclat.c001apk.nativeapp.domain.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class ReplyInteractionDecoderTest {
    private fun decode(body: String) = ApiDecoder.item(Json.parseToJsonElement(body))
    @Test fun explicitZeroAndOneHaveDistinctStates() {
        for ((wire, expected) in listOf("0" to ExplicitLikeState.NOT_LIKED, "1" to ExplicitLikeState.LIKED, "\"0\"" to ExplicitLikeState.NOT_LIKED, "\"1\"" to ExplicitLikeState.LIKED)) {
            val reply = requireNotNull(decode("""{"entityType":"feed_reply","userAction":{"like":$wire},"likenum":0,"replynum":2}""").replyInteraction)
            assertEquals(expected, reply.likeState); assertEquals(0, reply.likeCount); assertEquals(2, reply.replyCount)
        }
    }
    @Test fun absentOrMalformedActionStaysUnknownRegardlessOfCount() {
        for (wire in listOf("null", "{}", "{\"like\":null}", "{\"like\":true}", "{\"like\":2}", "{\"like\":{}}")) {
            val reply = requireNotNull(decode("""{"entityType":"feed_reply","userAction":$wire,"likenum":999}""").replyInteraction)
            assertEquals(ExplicitLikeState.UNKNOWN, reply.likeState); assertEquals(999, reply.likeCount)
        }
        assertEquals(ExplicitLikeState.UNKNOWN, decode("""{"entityType":"feed_reply"}""").replyInteraction?.likeState)
    }
    @Test fun absentAndInvalidCountsAreNotInventedZero() {
        val missing = requireNotNull(decode("""{"entityType":"feed_reply"}""").replyInteraction)
        assertNull(missing.likeCount); assertNull(missing.replyCount)
        val invalid = requireNotNull(decode("""{"entityType":"feed_reply","likenum":-1,"replynum":"oops"}""").replyInteraction)
        assertNull(invalid.likeCount); assertNull(invalid.replyCount)
    }
    @Test fun otherEntityCannotAcquireReplyMetadata() {
        for (kind in listOf("feed", "notification", "unknown"))
            assertNull(decode("""{"entityType":"$kind","userAction":{"like":1}}""").replyInteraction)
    }
    @Test fun nestedRepliesRetainTheirOwnStateNotTheirParentState() {
        val feed = decode("""{"entityType":"feed","userAction":{"like":1},"replyRows":[{"entityType":"feed_reply","id":2,"userAction":{"like":0}}]}""")
        assertEquals(ExplicitLikeState.LIKED, feed.feed?.likeState)
        assertEquals(ExplicitLikeState.NOT_LIKED, feed.replies.single().replyInteraction?.likeState)
    }
}
