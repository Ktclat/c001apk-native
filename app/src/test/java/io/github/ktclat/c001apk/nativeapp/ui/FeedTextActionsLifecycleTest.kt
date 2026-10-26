// SPDX-License-Identifier: AGPL-3.0-only
package io.github.ktclat.c001apk.nativeapp.ui

import androidx.compose.runtime.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.*
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.github.ktclat.c001apk.nativeapp.account.*
import io.github.ktclat.c001apk.nativeapp.data.*
import io.github.ktclat.c001apk.nativeapp.domain.ContentItem
import io.github.ktclat.c001apk.nativeapp.domain.FullPostBody
import io.github.ktclat.c001apk.nativeapp.platform.FeedTextTransfer
import io.github.ktclat.c001apk.nativeapp.social.SocialRepository
import okhttp3.OkHttpClient
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException

/** Actual composable callbacks: paused/disposed/changed owners cannot deliver stale text. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class FeedTextActionsLifecycleTest {
    @get:Rule val compose = createComposeRule()
    private val copied = mutableListOf<String>()
    private val shared = mutableListOf<String>()
    private var requests = 0
    private lateinit var accounts: AccountRuntime
    private lateinit var client: OkHttpClient
    private lateinit var actions: FeedTextActions
    private var shown by mutableStateOf(true)
    private var item by mutableStateOf(ContentItem("123", "feed", message = "合成动态"))
    private class Owner : LifecycleOwner {
        val registry = LifecycleRegistry.createUnsafe(this)
        override val lifecycle: Lifecycle get() = registry
    }
    private val owner = Owner()
    @Before fun setup() {
        val store = AccountStore(object : CredentialPersistence {
            override fun read(): ByteArray? = null
            override fun write(bytes: ByteArray) = Unit
            override fun reset() = Unit
        })
        client = OkHttpClient.Builder().addInterceptor { requests++; throw IOException("No networking") }.build()
        val login = HttpLoginTransport(client)
        val constructor = AccountRuntime::class.java.getDeclaredConstructor(AccountStore::class.java,
            HttpLoginTransport::class.java, LoginCoordinator::class.java, HttpCoolapkRepository::class.java,
            SocialRepository::class.java).apply { isAccessible = true }
        accounts = constructor.newInstance(store, login, LoginCoordinator(store, login, "synthetic"),
            HttpCoolapkRepository(client), SocialRepository(store) { CoolapkHttpTransport(client) })
    }
    @After fun cleanup() {
        client.dispatcher.cancelAll(); client.connectionPool.evictAll(); client.dispatcher.executorService.shutdownNow()
        assertEquals(0, requests)
    }
    private fun render(detail: Boolean = false) {
        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.RESUMED }
        val transfer = object : FeedTextTransfer {
            override fun copyLink(link: String) { copied += link }
            override fun shareText(text: String) { shared += text }
        }
        compose.setContent {
            val account by accounts.store.state.collectAsState()
            CompositionLocalProvider(LocalLifecycleOwner provides owner, LocalFeedTextTransfer provides transfer) {
                if (shown) {
                    val current = rememberFeedTextActions(item, accounts, account, detail)
                    SideEffect { actions = current }
                }
            }
        }
        compose.waitForIdle()
    }
    @Test fun pausedCallbackIsIgnoredAndResumingDoesNotReplay() {
        render()
        val copy = actions.copy!!
        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.STARTED; copy() }
        compose.waitForIdle(); assertTrue(copied.isEmpty())
        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.RESUMED }
        compose.waitForIdle(); assertTrue(copied.isEmpty())
        compose.runOnIdle { actions.copy!!.invoke() }
        compose.waitUntil(5_000) { copied.size == 1 }
    }
    @Test fun disposeAndSourceChangeInvalidateCapturedCallbacks() {
        render()
        val old = actions.copy!!
        compose.runOnIdle { item = item.copy(id = "456") }; compose.waitForIdle()
        compose.runOnIdle { old() }; compose.waitForIdle(); assertTrue(copied.isEmpty())
        val departed = actions.copy!!
        compose.runOnIdle { shown = false }; compose.waitForIdle()
        compose.runOnIdle { departed() }; compose.waitForIdle(); assertTrue(copied.isEmpty())
    }
    @Test fun accountChangeImmediatelyRejectsOldCallbackBeforeRecomposition() {
        render()
        val old = actions.copy!!
        compose.runOnIdle { accounts.store.useGuest(); old() }
        compose.waitForIdle(); assertTrue(copied.isEmpty())
        compose.runOnIdle { actions.copy!!.invoke() }
        compose.waitUntil(5_000) { copied.size == 1 }
    }
    @Test fun fullOrdinaryDetailSummaryUsesOwnedFullBodyNotListPreview() {
        item = item.copy(message = "", fullPostBody = FullPostBody.Available(" ".repeat(12_000) + "完整正文开头"))
        render(detail = true)
        compose.runOnIdle { actions.share!!.invoke() }
        compose.waitUntil(5_000) { shared.size == 1 }
        assertEquals("完整正文开头", shared.single().lineSequence().first())
    }
}
