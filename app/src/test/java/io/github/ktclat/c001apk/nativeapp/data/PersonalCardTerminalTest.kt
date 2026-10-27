package io.github.ktclat.c001apk.nativeapp.data

import io.github.ktclat.c001apk.nativeapp.domain.PageCursor
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class PersonalCardTerminalTest {
    @Test fun canonicalTerminalIsRemovedAndStopsOnlyAtPromotedListLevel() {
        val page = decodePersonalPage(Json.parseToJsonElement("""[
          {"id":"1","entityType":"feed","message":"保留的内容"},
          {"id":"wrapper","entityType":"card","entityTemplate":"listCard","entities":[
            {"id":"2","entityType":"card","entityTemplate":"noMoreDataCard","title":"没有更多"}]}
        ]"""), PageCursor())
        assertEquals(listOf("1"), page.items.map { it.recordId })
        assertNull(page.nextCursor)
    }
    @Test fun unknownContainerTerminalDoesNotTerminateOrFlatten() {
        val page = decodePersonalPage(Json.parseToJsonElement("""[
          {"id":"3","entityType":"card","entityTemplate":"future","entities":[
            {"id":"4","entityType":"card","entityTemplate":"noMoreDataCard"}]}
        ]"""), PageCursor())
        assertEquals(listOf("3"), page.items.map { it.recordId })
        assertEquals(1, page.items.single().content.children.size)
        assertEquals(PageCursor(2, "3", "3"), page.nextCursor)
    }
    @Test fun messageAndRefreshCardsAreNotTerminal() {
        for (template in listOf("messageCard", "refreshCard", "future")) {
            val page = decodePersonalPage(Json.parseToJsonElement("""[{"id":"7","entityType":"card","entityTemplate":"$template"}]"""), PageCursor())
            assertEquals(1, page.items.size)
            assertEquals(PageCursor(2, "7", "7"), page.nextCursor)
        }
    }
}
