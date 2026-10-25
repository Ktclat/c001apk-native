package io.github.ktclat.c001apk.nativeapp.ui

import io.github.ktclat.c001apk.nativeapp.domain.*
import org.junit.Assert.*
import org.junit.Test

class HighlightedRepliesTest {
    @Test fun pinnedOrderingPrefersLatestReplyApiBodyAndAccountActions() {
        val pinned = ContentItem("1", "feed_reply", message = "stale", replyInteraction = ReplyInteractionMetadata(ExplicitLikeState.UNKNOWN, 0, 0))
        val mine = ContentItem("2", "feed_reply", message = "my stale")
        val fresh = pinned.copy(message = "fresh", replyInteraction = ReplyInteractionMetadata(ExplicitLikeState.LIKED, 5, 2))
        val other = ContentItem("3", "feed_reply")
        assertEquals(listOf(fresh, mine, other), mergeHighlightedReplies(listOf(pinned, mine), listOf(other, fresh)))
        val refreshed = fresh.copy(message = "edited after refresh", replyInteraction = fresh.replyInteraction?.copy(likeState = ExplicitLikeState.NOT_LIKED, likeCount = 4))
        assertEquals(refreshed, mergeHighlightedReplies(listOf(pinned, mine), listOf(other, refreshed)).first())
    }
}
