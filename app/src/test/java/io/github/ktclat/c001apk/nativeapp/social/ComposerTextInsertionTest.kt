// SPDX-License-Identifier: AGPL-3.0-only
package io.github.ktclat.c001apk.nativeapp.social

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import io.github.ktclat.c001apk.nativeapp.account.AccountStore
import io.github.ktclat.c001apk.nativeapp.account.CredentialPersistence
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import org.junit.*
import org.junit.Assert.*

@OptIn(ExperimentalCoroutinesApi::class)
class ComposerTextInsertionTest {
    private val dispatcher = StandardTestDispatcher()
    private lateinit var accounts: AccountStore
    private lateinit var repository: SocialRepository
    private lateinit var model: ComposerViewModel
    @Before fun setup() {
        Dispatchers.setMain(dispatcher)
        accounts = AccountStore(object : CredentialPersistence {
            override fun read(): ByteArray? = null
            override fun write(bytes: ByteArray) = Unit
            override fun reset() = Unit
        })
        repository = SocialRepository(accounts) { error("Text editing must never request a transport") }
        model = ComposerViewModel(ComposerDraft(""), accounts.state.value.generation)
    }
    @After fun cleanup() { model.abandon(); Dispatchers.resetMain() }

    private fun assertInsertion(text: String, start: Int, end: Int, expected: String, caret: Int) {
        val inserted = requireNotNull(insertComposerToken(text, start, end, "[笑哭]"))
        assertEquals(expected, inserted.text)
        assertEquals(TextRange(caret), inserted.selection)
        assertNull(inserted.composition)
    }
    @Test fun emptyAndMidCaretInsertWithoutDeleting() {
        assertInsertion("", 0, 0, "[笑哭]", 4)
        assertInsertion("中文", 1, 1, "中[笑哭]文", 5)
    }
    @Test fun forwardAndReverseSelectionReplaceSameRange() {
        assertInsertion("甲乙丙丁", 1, 3, "甲[笑哭]丁", 5)
        assertInsertion("甲乙丙丁", 3, 1, "甲[笑哭]丁", 5)
    }
    @Test fun invalidEitherEndAppendsWithoutClampingOrDeleting() {
        listOf(-1 to -1, -1 to 2, 2 to -1, 0 to 99, 99 to 0, Int.MIN_VALUE to Int.MAX_VALUE).forEach { (a,b) ->
            assertInsertion("中文", a, b, "中文[笑哭]", 6)
        }
    }
    @Test fun surrogateInteriorCaretMovesRightWithoutDeletingEmoji() {
        assertInsertion("甲😀乙", 2, 2, "甲😀[笑哭]乙", 7)
    }
    @Test fun surrogateInteriorRangeExpandsOutwardInEitherDirection() {
        assertInsertion("甲😀乙", 2, 3, "甲[笑哭]乙", 5)
        assertInsertion("甲😀乙", 3, 2, "甲[笑哭]乙", 5)
        assertInsertion("甲😀乙", 1, 2, "甲[笑哭]乙", 5)
    }
    @Test fun combiningZwjFlagAndVariationClustersRemainWhole() {
        listOf("e\u0301", "👨‍👩‍👧‍👦", "🇨🇳", "✌️", "👩🏽‍💻").forEach { cluster ->
            val value = "甲${cluster}乙"
            for (offset in 2 until 1 + cluster.length) {
                assertInsertion(value, offset, offset, "甲$cluster[笑哭]乙", 1 + cluster.length + 4)
                assertInsertion(value, 1, offset, "甲[笑哭]乙", 5)
                assertInsertion(value, offset, 1, "甲[笑哭]乙", 5)
            }
        }
    }
    @Test fun boundaryCaretDoesNotJumpPastNextCluster() {
        assertInsertion("e\u0301中", 2, 2, "e\u0301[笑哭]中", 6)
        assertInsertion("😀中", 0, 0, "[笑哭]😀中", 4)
    }
    @Test fun explicitInsertionClearsCompositionAndHonorsCurrentSelection() {
        model.edit(TextFieldValue("甲乙丙", TextRange(2,1), TextRange(0,2)))
        assertTrue(model.insertTextEmoticon("[笑哭]", repository))
        assertEquals("甲[笑哭]丙", model.text)
        assertEquals(TextRange(5), model.input.selection); assertNull(model.input.composition)
    }
    @Test fun catalogIsExactFlutterOrderGroupsAndTokensWithoutMetaEntries() {
        val reference = javaClass.getResourceAsStream("/text-emoticons-flutter-e7db2976.json")!!.bufferedReader().use { it.readText() }
        val groups = Json.parseToJsonElement(reference).jsonObject
        assertEquals(listOf("常用", "牛牛", "酷币"), TextEmoticons.groups.map { it.label })
        TextEmoticons.groups.forEach { group -> assertEquals(groups.getValue(group.label).jsonArray.map { it.jsonPrimitive.content }, group.tokens) }
        assertEquals(listOf(108,32,43), TextEmoticons.groups.map { it.tokens.size })
        val tokens = TextEmoticons.groups.flatMap { it.tokens }
        assertEquals(183, tokens.distinct().size)
        listOf("[置顶]", "[楼主]", "[层主]", "[图片]", "[unknown]").forEach { assertFalse(TextEmoticons.contains(it)) }
        assertEquals(setOf("[酷币1$]", "[酷币2$]", "[酷币5$]", "[新酷币1$]", "[新酷币2$]", "[新酷币5$]"), tokens.filter { '$' in it }.toSet())
        assertTrue(tokens.none { '\\' in it })
        assertTrue(TextEmoticons.contains("[酷币1$]")); assertTrue(TextEmoticons.contains("[新酷币5€]"))
    }
    @Test fun unknownTokenCannotEnterThroughPickerAction() {
        model.edit("keep")
        assertFalse(model.insertTextEmoticon("[unknown]", repository)); assertEquals("keep", model.text)
    }
    @Test fun reaching500AndExceedingPreservesWholeTokensButNeverPreparesOversizeDraft() {
        model.edit("中".repeat(496)); assertTrue(model.insertTextEmoticon("[笑哭]", repository))
        assertEquals(500, model.characterCount); model.prepare(); assertNotNull(model.pending)
        model.dismissConfirmation()
        listOf(499,500).forEach { count ->
            model.edit("中".repeat(count)); assertTrue(model.insertTextEmoticon("[笑哭]", repository))
            assertEquals("中".repeat(count)+"[笑哭]", model.text); assertEquals(501, model.characterCount)
            model.prepare(); assertNull(model.pending); assertEquals(COMPOSER_TEXT_TOO_LONG, model.error)
        }
    }
    @Test fun resourceBoundAcceptsExactCapAndRejectsEntireOversizeEditWithoutLosingSelection() {
        model.edit("x".repeat(MAX_COMPOSER_CODE_UNITS - 4))
        assertTrue(model.insertTextEmoticon("[笑哭]", repository)); assertEquals(MAX_COMPOSER_CODE_UNITS, model.text.length)
        model.edit(model.input.copy(selection = TextRange(1), composition = TextRange(0,2)))
        val before = model.input
        assertFalse(model.insertTextEmoticon("[笑哭]", repository)); assertEquals(before, model.input)
        assertEquals(COMPOSER_PASTE_TOO_LARGE, model.error)
        assertNull(insertComposerToken("x".repeat(MAX_COMPOSER_CODE_UNITS+1),0,0,"[笑哭]"))
        model.edit(before.copy(selection = TextRange(0,4)))
        assertTrue(model.insertTextEmoticon("[笑哭]", repository)); assertEquals(MAX_COMPOSER_CODE_UNITS, model.text.length)
    }
    @Test fun confirmationFreezesInsertedTextAndCancelPreservesDraftSelection() {
        model.edit("甲乙"); model.edit(model.input.copy(selection = TextRange(1)))
        model.insertTextEmoticon("[笑哭]", repository); val before = model.input
        model.prepare(); assertEquals(before.text, model.pending!!.draft.text)
        assertFalse(model.insertTextEmoticon("[哈哈哈]", repository)); assertEquals(before, model.input)
        model.dismissConfirmation(); assertEquals(before, model.input)
        assertTrue(model.insertTextEmoticon("[酷币]", repository))
        assertEquals("甲[笑哭][酷币]乙", model.text)
    }
    @Test fun pendingMutationRejectsInsertionWithoutChangingFrozenDraft() = runTest(dispatcher) {
        model.edit("keep"); val gate = CompletableDeferred<Unit>()
        val work = launch { model.runner.submit { gate.await(); WriteOutcome.Confirmed() } }
        runCurrent(); assertFalse(model.editable())
        assertFalse(model.insertTextEmoticon("[笑哭]", repository)); assertEquals("keep", model.text)
        gate.complete(Unit); work.join()
        assertFalse(model.insertTextEmoticon("[笑哭]", repository))
    }
    @Test fun systemPickerAwaitingAndLaunchFailureAreExplicitlyBounded() {
        model.edit("keep"); assertTrue(model.beginImagePicker()); assertTrue(model.awaitingImagePicker)
        assertFalse(model.beginImagePicker()); assertFalse(model.insertTextEmoticon("[笑哭]", repository))
        model.edit("changed"); model.prepare(); assertEquals("keep", model.text); assertNull(model.pending)
        model.imagePickerLaunchFailed(); assertFalse(model.awaitingImagePicker); assertTrue(model.editable())
        assertTrue(model.insertTextEmoticon("[笑哭]", repository))
    }
    @Test fun staleAccountAndAbandonedPanelActionsCannotEditCurrentOwner() {
        model.edit("old"); accounts.useGuest()
        assertFalse(model.insertTextEmoticon("[笑哭]", repository)); assertEquals("old", model.text)
        val slot = ComposerSlotViewModel()
        val old = slot.acquire(accounts.state.value.generation, "old-route", ComposerDraft("old route"))
        val oldAction = { old.insertTextEmoticon("[笑哭]", repository) }
        old.beginImagePicker()
        val current = slot.acquire(accounts.state.value.generation, "new-route", ComposerDraft("new route"))
        assertFalse(old.awaitingImagePicker); assertFalse(oldAction()); assertEquals("new route", current.text)
        slot.release(current)
    }
}
