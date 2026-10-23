// SPDX-License-Identifier: AGPL-3.0-only
package io.github.ktclat.c001apk.nativeapp.ui

import io.github.ktclat.c001apk.nativeapp.data.ApiDecoder
import io.github.ktclat.c001apk.nativeapp.platform.*
import kotlinx.serialization.json.Json
import org.junit.Test
import org.junit.Assert.*

class IconLinkGridContractTest {
    @Test fun decoderPreservesOrderedPartialPagesAndPictures() {
        for (count in listOf(1,4,5,6,10)) {
            val item = iconFixture(count)
            assertEquals(count, item.children.size)
            assertFalse(item.childrenIncomplete)
            item.children.forEachIndexed { index, child ->
                assertEquals("icon-${index + 1}", child.id)
                assertEquals("https://fixture.invalid/icon-${index + 1}.png", child.imageUrls.single())
                assertTrue(parseAppLink(child.targetUrl!!) is AppLink.Internal)
            }
        }
    }
    @Test fun decoderFlagsMalformedAndBoundedChildrenWithoutInventingThem() {
        val child = """{"entityType":"iconLink","title":"入口","url":"/t/test"}"""
        for (entities in listOf("null", "{}", "[1,$child]", "[" + List(101) { child }.joinToString() + "]")) {
            val item = ApiDecoder.item(Json.parseToJsonElement("""{"entityTemplate":"iconLinkGridCard","entities":$entities}"""))
            assertTrue(entities, item.childrenIncomplete)
            assertTrue(item.children.size <= 100)
        }
    }
    @Test fun filteringDoesNotExposeOrCountRemovedChildren() {
        val item = iconFixture(6)
        val filtered = filterBlocked(listOf(item.copy(children = item.children.mapIndexed { i, child -> child.copy(userId = if (i == 5) "blocked" else "safe") })),
            io.github.ktclat.c001apk.nativeapp.local.Blacklist(userBlackList = listOf("blocked"))).single()
        assertEquals(5, filtered.children.size)
        assertFalse(filtered.childrenIncomplete)
        assertFalse(filtered.children.any { it.userId == "blocked" })
    }
    @Test fun labelsAreBoundedByCodePointWithoutSplittingSurrogates() {
        val eighty = "😀".repeat(80)
        assertEquals(eighty, iconLinkLabel(eighty).text)
        assertFalse(iconLinkLabel(eighty).truncated)
        val capped = iconLinkLabel(eighty + "终")
        assertEquals(eighty, capped.text)
        assertTrue(capped.truncated)
        assertEquals(80, capped.text.codePointCount(0, capped.text.length))
        assertEquals("a".repeat(79) + "😀", iconLinkLabel("a".repeat(79) + "😀尾").text)
        assertEquals("�", iconLinkLabel("\uD83D").text)
        assertEquals("�", iconLinkLabel("\uDE00").text)
        assertEquals(80, iconLinkLabel("长".repeat(12000)).text.length)
        assertTrue(iconLinkLabel("长".repeat(1000000)).accessible.length < 180)
        assertEquals("未命名入口", iconLinkLabel(" ").text)
    }
    @Test fun adaptiveColumnsNeverShrinkBelowTargets() {
        assertEquals(5, iconLinkColumns(373f, 1f))
        assertEquals(3, iconLinkColumns(320f, 1.6f))
        assertEquals(2, iconLinkColumns(240f, 2f))
        assertEquals(1, iconLinkColumns(48f, 2f))
    }
    @Test fun existingRouterDoesNotTrustMalformedOrLookalikeLinks() {
        for (raw in listOf("javascript:alert(1)", "data:text/plain,test", "file:///tmp/test", "//evil.test/feed/1", "https://a:b@coolapk.com/feed/1", "%bad"))
            assertEquals(raw, AppLink.Unsupported, parseAppLink(raw))
        assertEquals(AppLink.Internal("feed:1"), parseAppLink("/feed/1"))
        assertTrue(parseAppLink("https://coolapk.com.evil/feed/1") is AppLink.External)
    }
}
