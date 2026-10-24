package io.github.ktclat.c001apk.nativeapp.feature.feed

import io.github.ktclat.c001apk.nativeapp.domain.ContentItem
import io.github.ktclat.c001apk.nativeapp.domain.FeedMetadata
import io.github.ktclat.c001apk.nativeapp.domain.ExplicitLikeState
import io.github.ktclat.c001apk.nativeapp.domain.FeedReferenceKind
import io.github.ktclat.c001apk.nativeapp.domain.FeedReference as DomainFeedReference
import io.github.ktclat.c001apk.nativeapp.domain.FeedLinkMetadata
import io.github.ktclat.c001apk.nativeapp.domain.FeedRelationMetadata
import org.junit.Assert.*
import org.junit.Test

class FeedCardModelTest {
    @Test fun richProjectionPreservesTypedOwnershipAndOrdering() {
        val target = FeedRelationMetadata("product", "product", "product", "合成产品", "/product/product", "fixture://product")
        val relation = FeedRelationMetadata("topic", "topic", "topic", "合成话题", "/topic/topic")
        val card = ContentItem("owner", "feed", userName = "作者", userId = "author", feed = FeedMetadata(
            deviceTitle = "设备", infoHtml = "<b>来自动态</b>", ipLocation = "合成地点", pinned = true,
            likeState = ExplicitLikeState.LIKED, replyCount = 12, likeCount = 28,
            forward = DomainFeedReference(FeedReferenceKind.FORWARD, "forward", authorId = "quoted-author", author = "转发作者", body = "转发正文"),
            hotReply = DomainFeedReference(FeedReferenceKind.HOT_REPLY, "reply", "owner", author = "热评作者", body = "热评正文"),
            link = FeedLinkMetadata("链接标题", "https://example.invalid/reference", "fixture://link"),
            target = target, relations = listOf(relation),
        )).toFeedCardModel(100, detail = true)
        assertEquals("设备", card.device)
        assertEquals("<b>来自动态</b>", card.info)
        assertTrue(card.pinned)
        assertEquals(FeedLikeState.LIKED, card.likeState)
        assertEquals(12, card.replies); assertEquals(28, card.likes)
        assertEquals("发布于 合成地点", card.time)
        assertEquals("feed:forward", card.forward!!.destination())
        assertEquals("quoted-author", card.forward!!.authorId)
        assertEquals("replies:owner", card.hotReply!!.destination())
        assertEquals(FeedReferenceKind.HOT_REPLY, card.hotReply!!.kind)
        assertEquals(listOf("合成产品", "合成话题"), card.relations.map { it.title })
        assertEquals("fixture://product", card.relations.first().image)
        assertEquals("链接标题", card.link!!.title)
    }
    @Test fun missingRichCountsRemainUnknownAndUrlsAreNotInvented() {
        val card = ContentItem("1", "feed", replyCount = 99, likeCount = 88,
            targetUrl = "/not-an-extra-link", topicTags = listOf("not-a-relation"), feed = FeedMetadata()).toFeedCardModel(100)
        assertNull(card.replies); assertNull(card.likes)
        assertNull(card.link); assertTrue(card.relations.isEmpty())
        assertEquals(FeedLikeState.UNKNOWN, card.likeState)
        assertEquals(FeedLikeState.NOT_LIKED, ContentItem("1", "feed", feed = FeedMetadata(likeState = ExplicitLikeState.NOT_LIKED)).toFeedCardModel(100).likeState)
    }
    @Test fun deletedOrUnownedReferencesHaveNoInventedRoute() {
        assertNull(FeedReference("forward", "author", deleted = true).destination())
        assertNull(FeedReference("reply", "author", kind = FeedReferenceKind.HOT_REPLY).destination())
        assertNull(FeedReference("", "author").destination())
    }
    @Test fun countsNeverBecomeAccountState() {
        val card = ContentItem("1", "feed", likeCount = 900).toFeedCardModel(100)
        assertEquals(900, card.likes)
        assertEquals(FeedLikeState.UNKNOWN, card.likeState)
        assertFalse(card.likeState.canToggle())
        assertFalse(FeedLikeState.LOGIN_REQUIRED.canToggle())
        assertFalse(FeedLikeState.PENDING.canToggle())
        assertTrue(FeedLikeState.NOT_LIKED.canToggle())
        assertTrue(FeedLikeState.LIKED.canToggle())
    }
    @Test fun bridgePreservesMetadataAndNeverFlattensChildren() {
        val card = ContentItem("1", "feed", userName = "合成用户", userId = "synthetic", avatarUrl = "fixture://avatar", publishedAtSeconds = 100,
            children = listOf(ContentItem("nested", "feed_reply"))).toFeedCardModel(220)
        assertEquals("fixture://avatar", card.avatar)
        assertEquals("synthetic", card.authorId)
        assertEquals("2分钟前", card.time)
        assertEquals(1, card.unsupportedChildren)
        assertEquals("", card.title)
        assertNull(card.forward)
    }
    @Test fun missingCountsAndDatesAreNotManufactured() {
        assertEquals("", feedCount(null))
        assertEquals("1.2万", feedCount(12_345))
        assertEquals("21.4亿", feedCount(Int.MAX_VALUE))
        assertEquals("", feedRelativeTime(0, 100))
        assertEquals("刚刚", feedRelativeTime(200, 100))
        assertEquals("1小时前", feedRelativeTime(100, 3700))
    }
    @Test fun detailKeepsPublicationTimeAlongsideLocation() {
        val card = ContentItem("detail", "feed", publishedAtSeconds = 100,
            feed = io.github.ktclat.c001apk.nativeapp.domain.FeedMetadata(ipLocation = "合成地点")).toFeedCardModel(220, detail = true)
        assertTrue(card.time.contains("2分钟前"))
        assertTrue(card.time.contains("合成地点"))
    }
}
