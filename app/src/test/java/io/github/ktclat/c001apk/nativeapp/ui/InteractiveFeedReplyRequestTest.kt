// SPDX-License-Identifier: AGPL-3.0-only
package io.github.ktclat.c001apk.nativeapp.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import io.github.ktclat.c001apk.nativeapp.account.*
import io.github.ktclat.c001apk.nativeapp.data.*
import io.github.ktclat.c001apk.nativeapp.domain.*
import io.github.ktclat.c001apk.nativeapp.social.SocialRepository
import io.github.ktclat.c001apk.nativeapp.social.WriteOutcome
import kotlinx.coroutines.runBlocking
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/** Actual reply-row click -> real typed repository -> intercepted request. No sockets or real accounts. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class InteractiveFeedReplyRequestTest {
    @get:Rule val compose = createComposeRule()
    private val requests = CopyOnWriteArrayList<Request>()
    private val transportFactories = AtomicInteger()
    private val confirmed = AtomicInteger()
    private val navigations = CopyOnWriteArrayList<String>()
    @Volatile private var responseGate: CountDownLatch? = null
    private val clients = mutableListOf<OkHttpClient>()
    private lateinit var accounts: AccountRuntime

    @Before fun setup() {
        val store = AccountStore(object : CredentialPersistence {
            private var bytes: ByteArray? = null
            override fun read() = bytes?.copyOf()
            override fun write(bytes: ByteArray) { this.bytes = bytes.copyOf() }
            override fun reset() { bytes = null }
        })
        store.saveAuthenticated("1", "synthetic", "synthetic-device", listOf(Cookie.Builder()
            .name("token").value("synthetic-not-a-real-token").domain("coolapk.com").path("/").secure()
            .expiresAt(System.currentTimeMillis() + 3_600_000).build()), store.snapshot())
        val blocked = OkHttpClient.Builder().addInterceptor { throw IOException("Unexpected non-mutation request; networking is disabled") }.build()
        val mutation = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            check(request.url.host == "127.0.0.1")
            requests.add(request)
            if (responseGate?.await(5, TimeUnit.SECONDS) == false) throw IOException("Synthetic response gate was not released")
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("synthetic")
                .body("""{"data":{"likenum":7}}""".toResponseBody()).build()
            // Deliberately never chain.proceed: this fixture cannot open a network connection.
        }.build()
        clients.addAll(listOf(blocked, mutation))
        val endpoint = ApiEndpoints("http://127.0.0.1:9/".toHttpUrl(), "http://127.0.0.1:9/".toHttpUrl())
        val login = HttpLoginTransport(blocked)
        val social = SocialRepository(store) { snapshot ->
            transportFactories.incrementAndGet()
            CoolapkHttpTransport(client = mutation,
            endpoints = endpoint, requestSession = { store.requestSessionFor(snapshot) }) }
        // Keep production AccountRuntime construction private; test-only reflection injects synthetic collaborators.
        val constructor = AccountRuntime::class.java.getDeclaredConstructor(AccountStore::class.java,
            HttpLoginTransport::class.java, LoginCoordinator::class.java, HttpCoolapkRepository::class.java,
            SocialRepository::class.java).apply { isAccessible = true }
        accounts = constructor.newInstance(store, login, LoginCoordinator(store, login, "synthetic-device"),
            HttpCoolapkRepository(client = blocked, endpoints = endpoint), social)
    }
    @After fun cleanup() {
        responseGate?.countDown()
        clients.forEach { it.dispatcher.cancelAll(); it.connectionPool.evictAll(); it.dispatcher.executorService.shutdownNow() }
    }

    @Test fun notLikedReplyClickRequestsLikeReply() = clickAndAssert(ExplicitLikeState.NOT_LIKED, "/v6/feed/likeReply", "未点赞")
    @Test fun likedReplyClickRequestsUnlikeReply() = clickAndAssert(ExplicitLikeState.LIKED, "/v6/feed/unLikeReply", "已点赞")


    @Test fun pendingSecondTouchDoesNotSubmitAgain() {
        responseGate = CountDownLatch(1)
        showCard(ExplicitLikeState.NOT_LIKED)
        val like = compose.onNodeWithTag("reply-like:123")
        like.performScrollTo().performClick()
        compose.waitUntil(5_000) { requests.size == 1 }
        like.assertIsNotEnabled().assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "正在提交"))
        like.performTouchInput { click() }
        compose.waitForIdle()
        assertEquals(1, requests.size); assertEquals(0, confirmed.get())
        responseGate!!.countDown()
        compose.waitUntil(5_000) { confirmed.get() == 1 }
        assertEquals("/v6/feed/likeReply", requests.single().url.encodedPath)
        assertEquals(1, requests.size)
    }

    @Test fun unknownLikeStateDoesNotWrite() {
        showCard(ExplicitLikeState.UNKNOWN)
        val like = compose.onNodeWithTag("reply-like:123")
        like.performScrollTo().assertIsNotEnabled()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "当前点赞状态未知"))
        like.performTouchInput { click() }
        compose.waitForIdle()
        assertTrue(requests.isEmpty()); assertEquals(0, confirmed.get()); assertTrue(navigations.isEmpty())
    }

    @Test fun guestClickNavigatesToLoginWithoutWriting() {
        accounts.store.useGuest()
        showCard(ExplicitLikeState.NOT_LIKED)
        compose.onNodeWithTag("reply-like:123").performScrollTo().assertIsEnabled()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "登录后点赞")).performClick()
        compose.waitForIdle()
        assertEquals(listOf("login"), navigations.toList())
        assertTrue(requests.isEmpty()); assertEquals(0, confirmed.get())
    }


    @Test fun missingReplyActionDoesNotBecomeNotLiked() {
        showCard(ExplicitLikeState.NOT_LIKED, metadataPresent = false)
        compose.onNodeWithTag("reply-like:123").performScrollTo().assertIsNotEnabled().performTouchInput { click() }
        compose.waitForIdle()
        assertTrue(requests.isEmpty()); assertEquals(0, transportFactories.get())
    }
    @Test fun wrongEntityCannotEnterReplyLikeNamespace() {
        showCard(ExplicitLikeState.NOT_LIKED, kind = "feed")
        compose.onNodeWithTag("reply-like:123").performScrollTo().assertIsNotEnabled().performTouchInput { click() }
        compose.waitForIdle()
        assertTrue(requests.isEmpty()); assertEquals(0, transportFactories.get())
    }
    @Test fun staleRenderedGenerationCannotWriteEvenAfterSameAccountReturns() {
        showCard(ExplicitLikeState.NOT_LIKED)
        compose.runOnIdle { accounts.store.useGuest(); accounts.store.switchTo("1") }
        compose.onNodeWithTag("reply-like:123").performScrollTo().assertIsNotEnabled().performTouchInput { click() }
        compose.waitForIdle()
        assertTrue(requests.isEmpty()); assertEquals(0, confirmed.get()); assertEquals(0, transportFactories.get())
    }
    @Test fun expectedGenerationIsCheckedBeforeTransportIsConstructed() = runBlocking {
        val generation = accounts.store.state.value.generation
        accounts.store.useGuest(); accounts.store.switchTo("1")
        val outcome = accounts.socialRepository.like("123", reply = true, targetLiked = true, expectedGeneration = generation)
        assertTrue(outcome is WriteOutcome.Rejected)
        assertEquals(FailureKind.AUTHENTICATION, (outcome as WriteOutcome.Rejected).kind)
        assertTrue(requests.isEmpty()); assertEquals(0, transportFactories.get())
    }

    private fun clickAndAssert(initial: ExplicitLikeState, path: String, description: String) {
        assertEquals("1", accounts.store.state.value.activeId)
        showCard(initial)
        val like = compose.onNodeWithTag("reply-like:123")
        like.performScrollTo().assertIsEnabled()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, description)).performClick()
        compose.waitUntil(5_000) { requests.size == 1 && confirmed.get() == 1 }
        val request = requests.single()
        assertEquals("POST", request.method)
        assertEquals(path, request.url.encodedPath)
        assertEquals("123", request.url.queryParameter("id"))
        assertEquals("0", request.url.queryParameter("detail"))
        assertEquals("", (request.body as FormBody).value(0))
        assertEquals("trace", (request.body as FormBody).name(0))
        assertTrue(navigations.isEmpty())
        like.assertIsNotEnabled()
        compose.waitForIdle()
        assertEquals(1, requests.size); assertEquals(1, confirmed.get())
    }

    private fun showCard(initial: ExplicitLikeState, metadataPresent: Boolean = true, kind: String = "feed_reply") {
        val state = accounts.store.state.value
        val item = ContentItem("123", kind, message = "synthetic reply", userName = "synthetic author", userId = "2",
            replyInteraction = if (metadataPresent) ReplyInteractionMetadata(likeState = initial, likeCount = 7, replyCount = 0) else null)
        compose.setContent { MaterialTheme { Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            InteractiveFeedReplyRow(item, accounts, state, { navigations.add(it) }, {}, { confirmed.incrementAndGet() })
        } } }
    }
}
