// SPDX-License-Identifier: AGPL-3.0-only
package io.github.ktclat.c001apk.nativeapp.social

import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import io.github.ktclat.c001apk.nativeapp.account.AccountStore
import io.github.ktclat.c001apk.nativeapp.account.CredentialPersistence
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ComposerRouteLifecycleTest {
    @get:Rule val compose = createComposeRule()
    @Test fun savedRouteKeyCannotResurrectAbandonedDraftOrDisabledComposer() {
        val accounts = AccountStore(object : CredentialPersistence {
            override fun read(): ByteArray? = null
            override fun write(bytes: ByteArray) = error("No credential write in UI test")
            override fun reset() = Unit
        })
        val repository = SocialRepository(accounts) { error("Selection and editing must not access transport") }
        val visible = mutableStateOf(true)
        compose.setContent {
            val holder = rememberSaveableStateHolder()
            MaterialTheme { if (visible.value) holder.SaveableStateProvider("composer") {
                FeedComposerUi(repository, 0, ComposerDraft(""))
            } }
        }
        compose.onNode(hasSetTextAction()).performTextInput("discarded draft")
        compose.runOnIdle { visible.value = false }
        compose.waitForIdle()
        compose.runOnIdle { visible.value = true }
        compose.waitForIdle()
        compose.onNode(hasSetTextAction()).assert(hasText("discarded draft").not()).assertIsEnabled().performTextInput("fresh draft")
        compose.onNode(hasSetTextAction()).assertTextContains("fresh draft")
        compose.onNodeWithText("discarded draft").assertDoesNotExist()
    }
    @Test fun composerEditorAndConfirmationRemainReachableInShortLandscapeViewport() {
        val accounts = AccountStore(object : CredentialPersistence {
            override fun read(): ByteArray? = null
            override fun write(bytes: ByteArray) = error("No credential write")
            override fun reset() = Unit
        })
        val repository = SocialRepository(accounts) { error("Guest UI must not request transport") }
        compose.setContent { MaterialTheme { Box(Modifier.size(720.dp, 180.dp)) {
            FeedComposerUi(repository, 0, ComposerDraft(""))
        } } }
        compose.onNode(hasSetTextAction()).performScrollTo().assertIsDisplayed().performTextInput("draft")
        compose.onNodeWithText("检查并发送").performScrollTo().assertIsDisplayed().performClick()
        compose.onNodeWithText("确认发送").assertIsDisplayed()
    }

}
