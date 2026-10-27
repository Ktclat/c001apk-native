// SPDX-License-Identifier: AGPL-3.0-only
package io.github.ktclat.c001apk.nativeapp.local

import android.app.Activity
import android.content.Intent
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.core.app.ActivityOptionsCompat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-420dpi")
class BlacklistScreenTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private class Memory : JsonStateStore<LocalState> {
        override val data = MutableStateFlow(LocalState(blacklist = Blacklist(userBlackList = listOf("42"), topicBlackList = listOf("Android"))))
        var writes = 0
        var gate: CompletableDeferred<Unit>? = null
        override suspend fun update(transform: (LocalState) -> LocalState) {
            writes++; gate?.await(); data.value = LocalRepository.validateState(transform(data.value))
        }
    }
    private class Documents : BlacklistDocumentAccess {
        var reads = 0
        var text = "[\"43\"]"
        val writes = mutableListOf<Pair<Uri, String>>()
        override suspend fun read(uri: Uri): String { reads++; return text }
        override suspend fun write(uri: Uri, text: String) { writes += uri to text }
    }
    private inner class Picker : ActivityResultRegistry() {
        var code: Int? = null
        var lastCode: Int? = null
        val launches = mutableListOf<Intent>()
        override fun <I, O> onLaunch(requestCode: Int, contract: ActivityResultContract<I, O>, input: I, options: ActivityOptionsCompat?) {
            check(code == null); code = requestCode; lastCode = requestCode
            launches += contract.createIntent(compose.activity, input)
        }
        fun deliver(uri: Uri? = Uri.parse("content://synthetic/blacklist")) {
            val pending = requireNotNull(code); code = null
            check(dispatchResult(pending, if (uri == null) Activity.RESULT_CANCELED else Activity.RESULT_OK, uri?.let { Intent().setData(it) }))
        }
        fun duplicate() { dispatchResult(requireNotNull(lastCode), Activity.RESULT_OK, Intent().setData(Uri.parse("content://synthetic/duplicate"))) }
    }
    private val memory = Memory()
    private val repository = LocalRepository(memory)
    private val documents = Documents()
    private val picker = Picker()
    private lateinit var restorer: StateRestorationTester
    private val navigations = mutableListOf<String>()
    private val shown = mutableStateOf(true)
    private var hideOnNavigate = false
    private fun render() {
        val owner = object : ActivityResultRegistryOwner { override val activityResultRegistry = picker }
        restorer = StateRestorationTester(compose)
        restorer.setContent { CompositionLocalProvider(LocalActivityResultRegistryOwner provides owner) {
            MaterialTheme { if (shown.value) BlacklistScreen(repository, { shown.value = false }, { navigations += it; if (hideOnNavigate) shown.value = false }, documents) }
        } }
        awaitText("用户黑名单 · 1 条")
    }
    private fun awaitText(text: String) { compose.waitUntil(5_000) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() } }
    private fun action(text: String) = compose.onNodeWithTag("blacklist-list").performScrollToNode(hasText(text)).let { compose.onNodeWithText(text) }
    private fun topic() { action("话题关键词").performClick() }
    private fun user() { action("用户 ID").performClick() }

    @Test fun clearCancelAndRestoreKeepDialogScopeAndOnlyConfirmedCategoryChanges() {
        render()
        action("清空用户列表").performClick()
        compose.onNodeWithText("取消").performClick()
        compose.runOnIdle { assertEquals(0, memory.writes) }
        action("清空用户列表").performClick()
        restorer.emulateSavedInstanceStateRestore()
        compose.onNodeWithText("清空用户黑名单？").assertExists()
        compose.onNodeWithText("确认清空").performClick()
        awaitText("已清空用户黑名单")
        compose.runOnIdle { assertEquals(emptyList<String>(), memory.data.value.blacklist.userBlackList); assertEquals(listOf("Android"), memory.data.value.blacklist.topicBlackList) }
        topic(); action("清空话题列表").performClick()
        compose.onNodeWithText("取消").performClick()
        compose.runOnIdle { assertEquals(listOf("Android"), memory.data.value.blacklist.topicBlackList) }
    }
    @Test fun oldClearConfirmAndDismissCannotAffectReplacementOrReopenedDialog() {
        render()
        action("清空用户列表").performClick()
        val oldConfirm = compose.onNodeWithText("确认清空").fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
        val oldCancel = compose.onNodeWithText("取消").fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
        compose.onNodeWithText("取消").performClick()
        action("清空用户列表").performClick()
        compose.runOnIdle { oldConfirm(); oldCancel() }
        compose.onNodeWithText("清空用户黑名单？").assertExists()
        assertEquals(0, memory.writes)
        compose.onNodeWithText("取消").performClick()
        topic(); action("清空话题列表").performClick()
        compose.runOnIdle { oldConfirm(); oldCancel() }
        compose.onNodeWithText("清空话题黑名单？").assertExists()
        assertEquals(0, memory.writes)
        compose.onNodeWithText("确认清空").performClick()
        awaitText("已清空话题黑名单")
        assertEquals(listOf("42"), memory.data.value.blacklist.userBlackList)
        assertTrue(memory.data.value.blacklist.topicBlackList.isEmpty())
    }
    @Test fun retainedInputSemanticsUsesCurrentDraftAndDisposedRowsCannotMutateHiddenCategory() {
        render()
        compose.onNodeWithTag("blacklist-input").performTextInput("43")
        val oldAdd = action("添加").fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
        val oldRemove = compose.onNodeWithTag("blacklist-remove:42").fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
        topic()
        compose.runOnIdle { oldAdd(); oldRemove() }
        assertEquals(0, memory.writes)
        user(); compose.onNodeWithTag("blacklist-input").performTextReplacement("44")
        compose.runOnIdle { oldAdd() }
        // Material's retained semantics wrapper forwards to the live clickable node. It is a
        // current "add 44" action, not the old lambda; never mistake that for replaying "43".
        compose.waitUntil(5_000) { memory.data.value.blacklist.userBlackList.contains("44") }
        assertEquals(1, memory.writes)
        assertEquals(listOf("42", "44"), memory.data.value.blacklist.userBlackList)
        compose.runOnIdle { memory.data.value = LocalState(blacklist = Blacklist(topicBlackList = listOf("Android"))) }
        compose.waitForIdle()
        compose.runOnIdle { oldRemove() }
        assertEquals(1, memory.writes)
    }
    @Test fun pendingAddIsSingleFlightCapturesOriginalKindAndDoesNotEraseNewDraft() {
        render()
        compose.onNodeWithTag("blacklist-input").performTextInput("43")
        memory.gate = CompletableDeferred()
        action("添加").performClick()
        compose.waitUntil(5_000) { memory.writes == 1 }
        action("添加").assertIsNotEnabled().performTouchInput { repeat(3) { click() } }
        compose.onNodeWithTag("blacklist-list").performScrollToIndex(0)
        compose.onNodeWithTag("blacklist-input").performTextReplacement("44")
        topic()
        compose.onNodeWithTag("blacklist-input").performTextInput("topic draft")
        compose.runOnIdle { memory.gate!!.complete(Unit) }
        awaitText("已保存")
        user()
        compose.onNodeWithTag("blacklist-input").assertTextContains("44")
        compose.runOnIdle { assertEquals(1, memory.writes); assertEquals(listOf("42", "43"), memory.data.value.blacklist.userBlackList); assertEquals(listOf("Android"), memory.data.value.blacklist.topicBlackList) }
        topic(); compose.onNodeWithTag("blacklist-input").assertTextContains("topic draft")
    }
    @Test fun exportPinsCategoryAcrossTabSwitchAndRotationThenIgnoresDuplicateCallback() {
        render()
        action("导出用户列表").performClick()
        assertEquals("user-blacklist.json", picker.launches.single().getStringExtra(Intent.EXTRA_TITLE))
        topic(); restorer.emulateSavedInstanceStateRestore()
        action("导出话题列表").assertIsNotEnabled()
        compose.runOnIdle { picker.deliver() }
        awaitText("已导出")
        compose.runOnIdle {
            assertEquals(listOf("42"), Json.parseToJsonElement(documents.writes.single().second).jsonArray.map { it.jsonPrimitive.content })
            picker.duplicate()
        }
        compose.waitForIdle(); assertEquals(1, documents.writes.size)
        restorer.emulateSavedInstanceStateRestore() // A buffered duplicate still has no request owner.
        compose.waitForIdle(); assertEquals(1, documents.writes.size)
        action("完整备份").performClick()
        assertEquals("c001apk-blacklist.json", picker.launches.last().getStringExtra(Intent.EXTRA_TITLE))
        compose.runOnIdle { picker.deliver() }
        compose.waitUntil(5_000) { documents.writes.size == 2 }
        assertEquals(memory.data.value.blacklist, BlacklistJson.decode(documents.writes.last().second))
        action("导出话题列表").performClick()
        compose.runOnIdle { picker.deliver(null) }
        compose.onNodeWithText("已导出").assertDoesNotExist()
        assertEquals(2, documents.writes.size)
    }
    @Test fun importPinsLegacyKindAcrossRotationAndCancelOrMalformedFileLeavesDataIntact() {
        render()
        action("导入并合并").performClick()
        topic(); restorer.emulateSavedInstanceStateRestore()
        compose.runOnIdle { picker.deliver() }
        awaitText("已导入并合并")
        assertEquals(listOf("42", "43"), memory.data.value.blacklist.userBlackList)
        assertEquals(listOf("Android"), memory.data.value.blacklist.topicBlackList)
        val before = memory.data.value
        action("导入并合并").performClick()
        compose.runOnIdle { picker.deliver(null) }
        assertEquals(1, documents.reads); assertEquals(before, memory.data.value)
        documents.text = "{not json"
        action("导入并合并").performClick()
        compose.runOnIdle { picker.deliver() }
        compose.waitUntil(5_000) { compose.onAllNodesWithText("操作未完成", substring = true).fetchSemanticsNodes().isNotEmpty() }
        assertEquals(before, memory.data.value)
    }
    @Test fun retainedSameFramePickerClickCannotLaunchOrOverwriteASecondRequest() {
        render()
        val click = action("导出用户列表").fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
        compose.runOnIdle { click(); click() }
        assertEquals(1, picker.launches.size)
        compose.runOnIdle { picker.deliver(null) }
        val importClick = action("导入并合并").fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
        compose.runOnIdle { importClick(); importClick() }
        assertEquals(2, picker.launches.size)
        compose.runOnIdle { picker.deliver(null) }
        assertEquals(0, documents.reads); assertTrue(documents.writes.isEmpty())
    }
    @Test fun cancelledExportDoesNotWriteAndOversizedExportDoesNotOpenDestination() {
        render()
        action("导出用户列表").performClick()
        compose.runOnIdle { picker.deliver(null) }
        assertTrue(documents.writes.isEmpty())
        compose.runOnIdle { memory.data.value = LocalState(blacklist = Blacklist(topicBlackList = (1..2000).map { "中".repeat(195) + it })) }
        topic(); action("导出话题列表").performClick()
        compose.runOnIdle { picker.deliver() }
        compose.waitUntil(5_000) { compose.onAllNodesWithText("操作未完成", substring = true).fetchSemanticsNodes().isNotEmpty() }
        assertTrue(documents.writes.isEmpty())
    }
    @Test fun leavingDuringDeferredMutationDoesNotReportSuccessOrReplayTheMutation() {
        render()
        memory.gate = CompletableDeferred()
        compose.onNodeWithTag("blacklist-input").performTextInput("43")
        action("添加").performClick()
        compose.waitUntil(5_000) { memory.writes == 1 }
        compose.onNodeWithText("返回").performClick()
        compose.waitForIdle()
        compose.runOnIdle { memory.gate!!.complete(Unit); shown.value = true }
        awaitText("用户黑名单 · 1 条")
        assertEquals(listOf("42"), memory.data.value.blacklist.userBlackList)
        compose.onNodeWithText("已保存").assertDoesNotExist()
        assertEquals(1, memory.writes)
    }
    @Test fun orphanPickerResultAfterScreenRemovalDoesNotWriteToANewScreen() {
        render()
        action("导入并合并").performClick()
        compose.onNodeWithText("返回").performClick()
        compose.waitForIdle()
        compose.runOnIdle { picker.deliver(); shown.value = true }
        awaitText("用户黑名单 · 1 条")
        assertEquals(0, documents.reads)
        assertEquals(0, memory.writes)
        action("导入并合并").assertIsEnabled()
    }
    @Test fun navigationDoesNotUnblockAndDraftsSurviveStateRestoration() {
        render()
        compose.onNodeWithTag("blacklist-input").performTextInput("89")
        action("42").performClick()
        assertEquals(listOf("user:42"), navigations)
        assertEquals(0, memory.writes)
        topic(); compose.onNodeWithTag("blacklist-input").performTextInput("draft")
        restorer.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("blacklist-input").assertTextContains("draft")
        user(); compose.onNodeWithTag("blacklist-input").assertTextContains("89")
    }
}
