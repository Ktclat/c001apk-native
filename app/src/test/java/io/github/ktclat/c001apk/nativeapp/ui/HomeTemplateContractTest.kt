// SPDX-License-Identifier: AGPL-3.0-only
package io.github.ktclat.c001apk.nativeapp.ui

import io.github.ktclat.c001apk.nativeapp.data.ApiDecoder
import io.github.ktclat.c001apk.nativeapp.domain.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

internal fun homeTemplateFixtures(): List<ContentItem> = ApiDecoder.items(
    ApiDecoder.envelope(requireNotNull(HomeTemplateContractTest::class.java.getResource("/home-cards/templates.json")).readText()) as JsonArray)

class HomeTemplateContractTest {
    @Test fun allTenAdditionalSourceTemplatesDispatchOnlyTheirExactCardTypes() {
        val fixtures = homeTemplateFixtures()
        assertEquals(HomeCardTemplate.entries.toList(), fixtures.map(::homeCardTemplate))
        for (fixture in fixtures) assertNull(homeCardTemplate(fixture.copy(kind = "feed")))
        assertNull(homeCardTemplate(ContentItem("unknown", "card", entityTemplate = "futureCard")))
        assertNull(homeCardTemplate(ContentItem("icons", "card", entityTemplate = "iconLinkGridCard")))
    }
    @Test fun sourcePictureLogoAndAvatarRolesAreNotCollapsedIntoAnArbitraryFirstImage() {
        val fixtures = homeTemplateFixtures()
        for (template in listOf(HomeCardTemplate.CAROUSEL, HomeCardTemplate.SQUARE_SCROLL, HomeCardTemplate.IMAGE_TEXT_SCROLL)) {
            assertEquals("https://fixture.invalid/icon-1.png", homeCardEntries(fixtures.first { homeCardTemplate(it) == template }, template).first().image)
        }
        assertEquals("https://fixture.invalid/icon-2.png", homeCardEntries(fixtures[1], HomeCardTemplate.MINI_SCROLL).first().image)
        assertEquals("https://fixture.invalid/icon-2.png", homeCardEntries(fixtures[2], HomeCardTemplate.MINI_GRID).first().image)
        val user = homeCardEntries(fixtures[3], HomeCardTemplate.AVATAR_SCROLL).first()
        assertEquals("合成用户 1", user.label)
        assertEquals("https://fixture.invalid/icon-1.png", user.image)
    }
    @Test fun sourceEntityTitleSurvivesEvenWhenChildIsAFeedWithADifferentMessageTitle() {
        val card = homeTemplateFixtures()[5]
        assertEquals("", card.children.first().title)
        assertEquals(listOf("离线内容 1", "离线内容 2", "离线内容 3"),
            homeCardEntries(card, HomeCardTemplate.IMAGE_TEXT_SCROLL).map { it.label })
        val child = ApiDecoder.item(Json.parseToJsonElement("""{"id":7,"entityType":"feed","title":"栏目入口标题","message_title":"动态正文标题"}"""))
        assertEquals("动态正文标题", child.title)
        assertEquals("栏目入口标题", homeCardEntries(card.copy(children = listOf(child)), HomeCardTemplate.IMAGE_TEXT_SCROLL).single().label)
    }
    @Test fun miniScrollFallsBackToPictureButGridDoesNotInventAnotherImageRole() {
        val grid = homeTemplateFixtures()[2]
        val child = grid.children.first().copy(cardFields = HomeCardFields(picture = "https://fixture.invalid/icon-1.png"))
        assertEquals(child.cardFields!!.picture, homeCardEntries(grid.copy(children = listOf(child)), HomeCardTemplate.MINI_SCROLL).single().image)
        assertNull(homeCardEntries(grid.copy(children = listOf(child)), HomeCardTemplate.MINI_GRID).single().image)
    }
    @Test fun avatarEntityFieldsDoNotBorrowADifferentNestedFeedAuthor() {
        val card = homeTemplateFixtures()[3]
        val child = ApiDecoder.item(Json.parseToJsonElement("""{"id":7,"entityType":"feed","username":"入口用户","userAvatar":"https://fixture.invalid/icon-1.png","userInfo":{"username":"正文作者","userAvatar":"https://fixture.invalid/icon-2.png"}}"""))
        assertEquals("正文作者", child.userName)
        val entry = homeCardEntries(card.copy(children = listOf(child)), HomeCardTemplate.AVATAR_SCROLL).single()
        assertEquals("入口用户", entry.label)
        assertEquals("https://fixture.invalid/icon-1.png", entry.image)
    }
    @Test fun carouselUsesFlutterInternalOnlyFilterWithoutLosingOriginalIndexOrCrashingOnEmpty() {
        val card = homeTemplateFixtures()[0]
        val children = listOf(card.children[0].copy(targetUrl = "https://example.com/campaign"), card.children[1], card.children[2].copy(targetUrl = null))
        val entries = homeCardEntries(card.copy(children = children), HomeCardTemplate.CAROUSEL)
        assertEquals(listOf(1), entries.map { it.sourceIndex })
        assertEquals("/feed/9101", entries.single().url)
        assertTrue(homeCardEntries(card.copy(children = listOf(children.first())), HomeCardTemplate.CAROUSEL).isEmpty())
    }
    @Test fun malformedNestedAndUnknownTargetsNeverBecomeFeedRoutes() {
        val grid = homeTemplateFixtures()[2]
        val child = grid.children.first()
        val entries = homeCardEntries(grid.copy(children = listOf(
            child.copy(targetUrl = "javascript:alert(1)"), child.copy(children = listOf(child)),
            child.copy(entityTemplate = "futureCard"), child.copy(childrenIncomplete = true),
            child.copy(targetUrl = "https://user:pass@coolapk.com/feed/12"), child.copy(targetUrl = null),
        )), HomeCardTemplate.MINI_GRID)
        assertTrue(entries.none { it.supported })
        assertEquals(6, entries.size)
    }
    @Test fun leafEntityTypeAndIdNeverOverrideExplicitDestination() {
        val grid = homeTemplateFixtures()[2]
        val child = grid.children.first().copy(id = "444", kind = "unknown", targetUrl = "/feed/9119")
        val entry = homeCardEntries(grid.copy(children = listOf(child)), HomeCardTemplate.MINI_GRID).single()
        assertTrue(entry.supported)
        assertEquals("/feed/9119", entry.url)
    }
    @Test fun entriesRemainBoundedAndPreserveOddGridTail() {
        val grid = homeTemplateFixtures()[2]
        assertEquals(listOf(0, 1, 2), homeCardEntries(grid, HomeCardTemplate.MINI_GRID).map { it.sourceIndex })
        assertEquals(100, homeCardEntries(grid.copy(children = List(101) { grid.children.first() }), HomeCardTemplate.MINI_GRID).size)
    }
    @Test fun messageCardKeepsDescriptionInsteadOfUnrelatedMessageField() {
        val item = ApiDecoder.item(Json.parseToJsonElement("""{"id":"m","entityType":"card","entityTemplate":"messageCard","message":"different summary","description":"source message-card content"}"""))
        assertEquals("source message-card content", item.cardFields!!.description)
        assertEquals("different summary", item.message)
    }
    @Test fun unsupportedImageSchemesAreNeverSelected() {
        val item = ApiDecoder.item(Json.parseToJsonElement("""{"id":"m","pic":"file:///private/image","logo":"http://plain/image","entityType":"unknown"}"""))
        assertNull(item.cardFields!!.picture)
        assertNull(item.cardFields.logo)
    }
}
