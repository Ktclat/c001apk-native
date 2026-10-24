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

/** Actual card click -> real typed repository -> intercepted request. No sockets or real accounts. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class InteractiveFeedCardRequestTest {
    @get:Rule val compose = createComposeRule()
    private val requests = CopyOnWriteArrayList<Request>()
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
        val social = SocialRepository(store) { snapshot -> CoolapkHttpTransport(client = mutation,
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

    @Test fun notLikedCardClickRequestsLike() = clickAndAssert(ExplicitLikeState.NOT_LIKED, "/v6/feed/like", "未点赞")
    @Test fun likedCardClickRequestsUnlike() = clickAndAssert(ExplicitLikeState.LIKED, "/v6/feed/unlike", "已点赞")


    @Test fun pendingSecondTouchDoesNotSubmitAgain() {
        responseGate = CountDownLatch(1)
        showCard(ExplicitLikeState.NOT_LIKED)
        val like = compose.onNodeWithTag("feed-like:123")
        like.performScrollTo().performClick()
        compose.waitUntil(5_000) { requests.size == 1 }
        like.assertIsNotEnabled().assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "正在提交"))
        like.performTouchInput { click() }
        compose.waitForIdle()
        assertEquals(1, requests.size); assertEquals(0, confirmed.get())
        responseGate!!.countDown()
        compose.waitUntil(5_000) { confirmed.get() == 1 }
        assertEquals("/v6/feed/like", requests.single().url.encodedPath)
        assertEquals(1, requests.size)
    }

    @Test fun unknownLikeStateDoesNotWrite() {
        showCard(ExplicitLikeState.UNKNOWN)
        val like = compose.onNodeWithTag("feed-like:123")
        like.performScrollTo().assertIsNotEnabled()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "当前点赞状态未知"))
        like.performTouchInput { click() }
        compose.waitForIdle()
        assertTrue(requests.isEmpty()); assertEquals(0, confirmed.get()); assertTrue(navigations.isEmpty())
    }

    @Test fun guestClickNavigatesToLoginWithoutWriting() {
        accounts.store.useGuest()
        showCard(ExplicitLikeState.NOT_LIKED)
        compose.onNodeWithTag("feed-like:123").performScrollTo().assertIsEnabled()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "登录后点赞")).performClick()
        compose.waitForIdle()
        assertEquals(listOf("login"), navigations.toList())
        assertTrue(requests.isEmpty()); assertEquals(0, confirmed.get())
    }

    private fun clickAndAssert(initial: ExplicitLikeState, path: String, description: String) {
        assertEquals("1", accounts.store.state.value.activeId)
        showCard(initial)
        val like = compose.onNodeWithTag("feed-like:123")
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

    private fun showCard(initial: ExplicitLikeState) {
        val state = accounts.store.state.value
        val item = ContentItem("123", "feed", message = "synthetic feed", userName = "synthetic author", userId = "2",
            feed = FeedMetadata(likeState = initial, likeCount = 7, replyCount = 0))
        compose.setContent { MaterialTheme { Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            InteractiveFeedCard(item, accounts, state, { navigations.add(it) }, {}, {}, { confirmed.incrementAndGet() })
        } } }
    }
}
