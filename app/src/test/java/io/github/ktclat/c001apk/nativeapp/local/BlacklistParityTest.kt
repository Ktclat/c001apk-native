// SPDX-License-Identifier: AGPL-3.0-only
package io.github.ktclat.c001apk.nativeapp.local

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class BlacklistParityTest {
    private class Memory(initial: LocalState) : JsonStateStore<LocalState> {
        override val data = MutableStateFlow(initial)
        override suspend fun update(transform: (LocalState) -> LocalState) { data.value = LocalRepository.validateState(transform(data.value)) }
    }
    @Test fun categoryExportsAreFlutterArraysAndNeverContainTheOtherCategory() {
        val topics = listOf("a/b #?% 中", "引号\"和反斜杠\\", "😀")
        val source = Blacklist(userBlackList = listOf("1", "002"), topicBlackList = topics)
        val users = BlacklistJson.encodeCategory(source, BlacklistKind.USER)
        val topicText = BlacklistJson.encodeCategory(source, BlacklistKind.TOPIC)
        assertEquals(listOf("1", "002"), Json.parseToJsonElement(users).jsonArray.map { it.jsonPrimitive.content })
        assertEquals(topics, Json.parseToJsonElement(topicText).jsonArray.map { it.jsonPrimitive.content })
        assertEquals(Blacklist(userBlackList = source.userBlackList), BlacklistJson.decode(users, true))
        assertEquals(Blacklist(topicBlackList = topics), BlacklistJson.decode(topicText, false))
        assertEquals(source, BlacklistJson.decode(BlacklistJson.encode(source)))
    }
    @Test fun selectedClearPreservesOtherListsAndMetadata() = runTest {
        val initial = LocalState(favorites = listOf(LocalFeedEntry("1")), history = listOf(LocalFeedEntry("2")),
            follows = listOf(LocalFollowUser("3")), searchHistory = listOf("keep"),
            blacklist = Blacklist(userBlackList = listOf("4"), topicBlackList = listOf("Android")))
        for (kind in BlacklistKind.entries) {
            val repo = LocalRepository(Memory(initial))
            repo.clearBlacklist(kind)
            val expected = initial.copy(blacklist = if (kind == BlacklistKind.USER) initial.blacklist.copy(userBlackList = emptyList())
                else initial.blacklist.copy(topicBlackList = emptyList()))
            assertEquals(expected, repo.state.first())
        }
    }
    @Test fun allExportsAreBoundedAndRoundTrippable() {
        val large = Blacklist(topicBlackList = (1..2000).map { "中".repeat(195) + it.toString() })
        assertTrue(large.topicBlackList.all { it.length <= 200 })
        for (encode in listOf<() -> String>({ BlacklistJson.encode(large) }, { BlacklistJson.encodeCategory(large, BlacklistKind.TOPIC) })) {
            try { encode(); fail("An export larger than the import limit must be rejected") } catch (_: IllegalArgumentException) { }
        }
        assertEquals("[]", BlacklistJson.encodeCategory(large, BlacklistKind.USER))
    }
    @Test fun emptyCategoryExportIsAnExplicitEmptyArrayAndDoesNotClearImportTarget() = runTest {
        val repo = LocalRepository(Memory(LocalState(blacklist = Blacklist(userBlackList = listOf("9")))))
        repo.importBlacklist(BlacklistJson.encodeCategory(Blacklist(), BlacklistKind.USER), true)
        assertEquals(listOf("9"), repo.state.first().blacklist.userBlackList)
    }
    @Test fun nativeRoutesPreserveExactIdentifiersWithoutDoubleUrlEncoding() {
        assertEquals("user:00042", BlacklistKind.USER.route("00042"))
        assertEquals("topic:a/b #?% 中", BlacklistKind.TOPIC.route("a/b #?% 中"))
    }
}
