// SPDX-License-Identifier: AGPL-3.0-only
package io.github.ktclat.c001apk.nativeapp.ui

import io.github.ktclat.c001apk.nativeapp.data.ApiDecoder
import io.github.ktclat.c001apk.nativeapp.domain.*
import io.github.ktclat.c001apk.nativeapp.local.Blacklist
import io.github.ktclat.c001apk.nativeapp.platform.AppLink
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

internal fun embeddedCardFixture(name: String): List<ContentItem> = ApiDecoder.items(ApiDecoder.envelope(
    requireNotNull(EmbeddedCardContractTest::class.java.getResource("/embedded-cards/$name.json")).readText()) as JsonArray)

class EmbeddedCardContractTest {
    @Test fun exactListContainersExpandInSourceOrderWithoutChangingRawCursorIdentities() {
        val source = embeddedCardFixture("supported-list-card")
        val rows = expandSupportedListCards(source)
        assertEquals(listOf("audit-icons", "3001"), rows.map { it.id })
        assertEquals("iconLinkGridCard", rows[0].entityTemplate)
        assertEquals(3, rows[0].children.size)
        assertEquals("wrapper-1", source.single().id)
        assertEquals("wrapper-2", source.single().children[1].id)
        assertEquals(listOf("128讨论", "1.2万下载"), rows[1].entityCard!!.metrics)
        assertEquals("https://fixture.invalid/icon-7.png", rows[1].entityCard!!.image)
    }
    @Test fun unknownContainersFeedChildrenAndRepliesAreNeverPromotedToPeers() {
        val leaf = ContentItem("leaf", "feed", message = "child")
        val unknown = ContentItem("unknown", "card", entityTemplate = "futureTemplate", children = listOf(leaf))
        val feed = ContentItem("feed", "feed", children = listOf(leaf), replies = listOf(leaf))
        assertEquals(listOf(unknown, feed), expandSupportedListCards(listOf(unknown, feed)))
        val misleadingFeed = unknown.copy(entityTemplate = "listCard", kind = "feed")
        assertEquals(listOf(misleadingFeed), expandSupportedListCards(listOf(misleadingFeed)))
    }
    @Test fun recursiveBlacklistIsAppliedBeforeExpandingKnownContainers() {
        val blocked = ContentItem("1", "feed", userId = "99")
        val visible = ContentItem("2", "feed", userId = "42")
        val wrapper = ContentItem("outer", "card", entityTemplate = "listCard", children = listOf(blocked, visible))
        assertEquals(listOf(visible), expandSupportedListCards(filterBlocked(listOf(wrapper), Blacklist(userBlackList = listOf("99")))))
    }
    @Test fun anonymousSiblingsKeepTheirOrderAndSeparateChildren() {
        val first = ContentItem("", "card", entityTemplate = "iconLinkGridCard", children = listOf(ContentItem("first", "iconLink")))
        val second = first.copy(children = listOf(ContentItem("second", "iconLink")))
        val wrapper = ContentItem("outer", "card", entityTemplate = "listCard", children = listOf(first, second))
        assertEquals(listOf("first", "second"), contentListItems(listOf(wrapper)).map { it.children.single().id })
    }
    @Test fun distinctSourceEntityIdsAreNotCollapsedBySharedRecordIdsAndKeepCursorIds() {
        val rows = ApiDecoder.items(Json.parseToJsonElement("""[{"entityType":"card","entityTemplate":"iconLinkGridCard","id":0,"entityId":"first"},{"entityType":"card","entityTemplate":"iconLinkGridCard","id":0,"entityId":"second"}]""") as JsonArray)
        assertEquals(listOf("0", "0"), rows.map { it.id })
        assertEquals(listOf("first", "second"), contentListItems(rows).map { it.sourceEntityId })
        assertEquals(2, contentListItems(rows).map(::contentListIdentity).toSet().size)
    }
    @Test fun malformedAndDepthLimitedContainersRemainHonestAndBounded() {
        val malformed = ApiDecoder.item(Json.parseToJsonElement("""{"entityType":"card","entityTemplate":"listCard","entities":[null,{"id":"ok","entityType":"apk","title":"A"}]}"""))
        val rows = expandSupportedListCards(listOf(malformed))
        assertEquals("ok", rows.first().id)
        assertTrue(rows.last().childrenIncomplete)
        var deep = ContentItem("tail", "apk")
        repeat(12) { deep = ContentItem("depth-$it", "card", entityTemplate = "listCard", children = listOf(deep)) }
        assertEquals("listCard", expandSupportedListCards(listOf(deep)).single().entityTemplate)
        val many = expandSupportedListCards(List(2_001) { ContentItem("$it", "apk") })
        assertEquals(2_001, many.size)
        assertTrue(many.last().childrenIncomplete)
        val fullIncomplete = ContentItem("full", "card", entityTemplate = "listCard", childrenIncomplete = true,
            children = List(2_000) { ContentItem("$it", "apk") })
        assertTrue(expandSupportedListCards(listOf(fullIncomplete)).last().childrenIncomplete)
        val sameIdChild = ContentItem("0", "card", entityTemplate = "iconLinkGridCard")
        val incompleteWrapper = ContentItem("0", "card", entityTemplate = "listCard", children = listOf(sameIdChild), childrenIncomplete = true)
        val kept = contentListItems(listOf(incompleteWrapper))
        assertEquals(2, kept.size); assertTrue(kept.last().childrenIncomplete)
        assertEquals(2, kept.map(::contentListIdentity).toSet().size)
        assertNotEquals(contentListIdentity(sameIdChild.copy(title = ":incomplete-list")), contentListIdentity(incompleteWrapper))
        assertNotEquals(contentListIdentity(sameIdChild.copy(id = "a:b", title = "c")), contentListIdentity(sameIdChild.copy(id = "a", title = "b:c")))
    }
    @Test fun sourceKnownEntityIconsMetricsAndContactOwnershipArePreserved() {
        val rows = embeddedCardFixture("supported-entity-icons")
        assertEquals(listOf("旅行摄影师", "开源观察员", "周末摄影精选"), rows.map(::entityCardTitle))
        assertEquals(listOf("86关注", "1280粉丝"), rows[0].entityCard!!.metrics)
        assertEquals(listOf("120关注", "980粉丝"), rows[1].entityCard!!.metrics)
        assertEquals(listOf("公开", "128人关注", "24个内容"), rows[2].entityCard!!.metrics)
        assertEquals(listOf(4,5,6).map { "https://fixture.invalid/icon-$it.png" }, rows.map { it.entityCard!!.image })
        assertEquals(AppLink.Internal("user:1002"), entityCardDestination(rows[1]))
        assertEquals(AppLink.Internal("collection:2001"), entityCardDestination(rows[2]))
        val contact = ApiDecoder.item(Json.parseToJsonElement("""{"entityType":"contacts","uid":99,"fUserInfo":{"uid":42,"username":"Friend"}}"""))
        assertEquals("42", contact.userId)
        assertEquals(AppLink.Internal("user:42"), entityCardDestination(contact))
        val split = ApiDecoder.item(Json.parseToJsonElement("""{"entityType":"contacts","userInfo":{"username":"First","follow":0},"fUserInfo":{"uid":77,"username":"Fallback","fans":3,"userAvatar":"https://fixture.invalid/icon-5.png"}}"""))
        assertEquals("First", split.userName); assertEquals("77", split.userId)
        assertEquals("https://fixture.invalid/icon-5.png", split.avatarUrl)
        assertEquals(listOf("0关注", "3粉丝"), split.entityCard!!.metrics)
    }
    @Test fun recentProductAndTopicUseActualSnakeCaseFieldsAndMissingCountsStayAbsent() {
        val recent = ApiDecoder.item(Json.parseToJsonElement("""{"entityType":"recentHistory","title":"Friend","logo":"https://fixture.invalid/icon-1.png","target_type":"user","target_type_title":"用户","follow_num":2,"fans_num":3,"url":"/u/42"}"""))
        assertEquals("用户: Friend", entityCardTitle(recent)); assertTrue(recent.entityCard!!.circular)
        assertEquals(listOf("2关注", "3粉丝"), recent.entityCard.metrics)
        val product = ApiDecoder.item(Json.parseToJsonElement("""{"entityType":"product","hot_num_txt":"1.2万","feed_comment_num_txt":42}"""))
        assertEquals(listOf("1.2万热度", "42讨论"), product.entityCard!!.metrics)
        val topic = ApiDecoder.item(Json.parseToJsonElement("""{"entityType":"topic","hot_num_txt":2,"commentnum_txt":4}"""))
        assertEquals(listOf("2热度", "4讨论"), topic.entityCard!!.metrics)
        assertTrue(ApiDecoder.item(Json.parseToJsonElement("""{"entityType":"apk","commentnum":null}""")).entityCard!!.metrics.isEmpty())
    }
    @Test fun unsafeLinksAndImagesNeverBecomeAnEnabledDestinationOrImageRole() {
        val row = ApiDecoder.item(Json.parseToJsonElement("""{"entityType":"apk","id":42,"title":"A","url":"javascript:alert(1)","logo":"file:///private","pic":"https://fixture.invalid/icon-1.png"}"""))
        assertEquals(AppLink.Unsupported, entityCardDestination(row)); assertNull(row.entityCard!!.image)
        assertNull(ApiDecoder.item(Json.parseToJsonElement("""{"entityType":"unknown","logo":"https://fixture.invalid/icon-1.png"}""")).entityCard)
        val longUrl = "https://example.invalid/" + "a".repeat(200)
        val collection = ApiDecoder.item(buildJsonObject { put("entityType", "collection"); put("cover_pic", longUrl) })
        assertEquals(longUrl, collection.entityCard!!.image)
    }
}
