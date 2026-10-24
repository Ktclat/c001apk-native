// SPDX-License-Identifier: AGPL-3.0-only
package io.github.ktclat.c001apk.nativeapp.social

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class GenerationEntryUiTest {
    @get:Rule val compose = createComposeRule()
    @Test fun staleComposerClickDoesNotConstructTransportOrSubmit() {
        val accounts = syntheticAccounts()
        val generation = accounts.state.value.generation
        val factories = AtomicInteger()
        val repository = SocialRepository(accounts) { factories.incrementAndGet(); error("Stale UI must not construct transport") }
        compose.setContent { MaterialTheme { SocialComposerScreen(repository, generation, ComposerDraft("synthetic draft")) } }
        compose.runOnIdle { accounts.switchTo("2") }
        compose.onNodeWithText("检查并发送").performScrollTo().assertIsDisplayed().performClick()
        compose.onNodeWithText("确认发送").assertIsDisplayed().performClick()
        compose.waitForIdle()
        assertEquals(0, factories.get())
        compose.onNodeWithText("synthetic draft").assertExists()
    }
}
