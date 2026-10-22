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
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.activity.ComponentActivity
import io.github.ktclat.c001apk.nativeapp.AppContainer
import io.github.ktclat.c001apk.nativeapp.settings.AppSettings
import io.github.ktclat.c001apk.nativeapp.local.loadStates
import io.github.ktclat.c001apk.nativeapp.local.LocalLoadState
import kotlinx.coroutines.flow.first
import java.lang.reflect.Proxy
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

/** NativeApp real route -> rendered reply -> real typed write, intercepted without sockets. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w393dp-h852dp-420dpi")
class NativeReplyRouteRequestTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val requests = CopyOnWriteArrayList<Request>()
    private val transportFactories = AtomicInteger()
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


    private val replyReads = AtomicInteger()
    private var diagnosticModel: BrowseViewModel? = null
    private fun showRoute(route: String, initial: ExplicitLikeState) {
        val container = AppContainer(compose.activity.application)
        container.nativeUpdates.enabled = false
        // This is an interaction fixture, not a cold-start readiness fixture.
        runBlocking { kotlinx.coroutines.withTimeout(5_000) { container.local.loadStates().first { it is LocalLoadState.Ready } } }
        val post = ContentItem("900", "feed", userName = "合成作者", message = "合成动态", userId = "2",
            feed = FeedMetadata(replyCount = 1))
        val repository = Proxy.newProxyInstance(CoolapkRepository::class.java.classLoader, arrayOf(CoolapkRepository::class.java)) { _, method, _ ->
            when (method.name) {
                "feedDetail" -> ApiResult.Success(post)
                "orderedReplies", "replies", "replyThread" -> {
                    replyReads.incrementAndGet()
                    val refreshed = if (requests.isEmpty()) initial else when (initial) {
                        ExplicitLikeState.NOT_LIKED -> ExplicitLikeState.LIKED
                        ExplicitLikeState.LIKED -> ExplicitLikeState.NOT_LIKED
                        else -> initial
                    }
                    ApiResult.Success(Page(listOf(ContentItem("123", "feed_reply", userName = "合成评论者", userId = "3", message = "合成评论",
                        replyInteraction = ReplyInteractionMetadata(refreshed, 7, 0))), null))
                }
                else -> throw AssertionError("Unexpected root browse call: ${method.name}")
            }
        } as CoolapkRepository
        val model = BrowseViewModel(repository)
        diagnosticModel = model
        val snapshot = accounts.store.state.value
        compose.setContent { MaterialTheme {
            NativeApp(model, container, AppSettings(floatingNavigation = true, dynamicColor = false), accounts, snapshot, initialRoute = route)
        } }
        compose.waitUntil(15_000) { compose.onAllNodesWithTag("reply-like:123").fetchSemanticsNodes().size == 1 }
        compose.onNodeWithTag("reply-like:123").performScrollTo().assertIsDisplayed()
    }
    /** Require visible refreshed state plus original counters within the unchanged deadline. Failure inspection never rescues a test. */
    private fun awaitAcknowledgedRefresh(label: String, expectedState: String, predicate: () -> Boolean) {
        try { compose.waitUntil(5_000) {
            // Observe actual UI first so the test services queued Main work as well as Compose frames.
            // Counter-only polling left a scheduled viewModelScope refresh pending in build61.
            val target = hasTestTag("reply-like:123") and SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, expectedState)
            val visible = compose.onAllNodes(target).fetchSemanticsNodes().size == 1 && compose.onNode(target).isDisplayed()
            predicate() && visible
        } }
        catch (failure: ComposeTimeoutException) {
            fun snapshot() = "requests=${requests.map { it.method + " " + it.url.encodedPath }}, factories=${transportFactories.get()}, reads=${replyReads.get()}, generation=${accounts.store.state.value.generation}, states=${diagnosticModel?.states?.value?.mapValues { (_, s) -> "loaded=${s.loaded}, loading=${s.loading}, items=${s.items.size}, error=${s.error}" }}"
            // Capture counters BEFORE semantics synchronization; preserve whether inspection pumps a queued callback.
            val before = snapshot()
            val tree = runCatching { compose.onRoot(useUnmergedTree = true).printToString() }.getOrElse { "tree unavailable: ${it.javaClass.simpleName}" }
            failure.addSuppressed(AssertionError("Acknowledged refresh timed out: $label\nbefore=$before\nafter=${snapshot()}\n$tree"))
            throw failure
        }
    }
    private fun clickAndAssert(route: String, initial: ExplicitLikeState, path: String) {
        showRoute(route, initial)
        compose.onNodeWithTag("reply-like:123").assertIsEnabled().performClick()
        awaitAcknowledgedRefresh("$route:$initial", if (initial == ExplicitLikeState.NOT_LIKED) "已点赞" else "未点赞") { requests.size == 1 && replyReads.get() >= 2 }
        val request = requests.single()
        assertEquals("POST", request.method)
        assertEquals(path, request.url.encodedPath)
        assertEquals("123", request.url.queryParameter("id"))
        assertEquals("0", request.url.queryParameter("detail"))
        val body = request.body as FormBody
        assertEquals(1, body.size); assertEquals("trace", body.name(0)); assertEquals("", body.value(0))
        assertEquals(1, transportFactories.get())
        val nextLabel = if (initial == ExplicitLikeState.NOT_LIKED) "已点赞" else "未点赞"
        compose.waitUntil(5_000) { compose.onAllNodes(hasTestTag("reply-like:123") and SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, nextLabel)).fetchSemanticsNodes().size == 1 }
    }
    @Test fun detailUnlikedWritesLikeReplyAndRefreshesActualComments() = clickAndAssert("feed:900", ExplicitLikeState.NOT_LIKED, "/v6/feed/likeReply")
    @Test fun detailLikedWritesUnLikeReplyAndRefreshesActualComments() = clickAndAssert("feed:900", ExplicitLikeState.LIKED, "/v6/feed/unLikeReply")
    @Test fun repliesUnlikedWritesLikeReplyAndRefreshesActualList() = clickAndAssert("replies:900", ExplicitLikeState.NOT_LIKED, "/v6/feed/likeReply")
    @Test fun repliesLikedWritesUnLikeReplyAndRefreshesActualList() = clickAndAssert("replies:900", ExplicitLikeState.LIKED, "/v6/feed/unLikeReply")
    @Test fun threadUnlikedWritesLikeReplyAndRefreshesActualList() = clickAndAssert("thread:123", ExplicitLikeState.NOT_LIKED, "/v6/feed/likeReply")
    @Test fun threadLikedWritesUnLikeReplyAndRefreshesActualList() = clickAndAssert("thread:123", ExplicitLikeState.LIKED, "/v6/feed/unLikeReply")
    @Test fun unknownStateInActualDetailCannotWrite() {
        showRoute("feed:900", ExplicitLikeState.UNKNOWN)
        compose.onNodeWithTag("reply-like:123").assertIsNotEnabled().performTouchInput { click() }
        compose.waitForIdle(); assertTrue(requests.isEmpty()); assertEquals(0, transportFactories.get())
    }
    @Test fun actualThreadPendingSecondTouchDoesNotDuplicateRequest() {
        responseGate = CountDownLatch(1)
        showRoute("thread:123", ExplicitLikeState.NOT_LIKED)
        val like = compose.onNodeWithTag("reply-like:123")
        like.performClick()
        compose.waitUntil(5_000) { requests.size == 1 }
        like.assertIsNotEnabled().performTouchInput { click() }
        compose.waitForIdle(); assertEquals(1, requests.size); assertEquals(1, replyReads.get())
        responseGate!!.countDown()
        awaitAcknowledgedRefresh("pending-thread", "已点赞") { replyReads.get() >= 2 }
        assertEquals(1, requests.size)
    }
    @Test fun staleActualDetailAfterAccountSwitchCannotWrite() {
        showRoute("feed:900", ExplicitLikeState.NOT_LIKED)
        compose.runOnIdle { accounts.store.useGuest() }
        compose.onNodeWithTag("reply-like:123").assertIsNotEnabled().performTouchInput { click() }
        compose.waitForIdle(); assertTrue(requests.isEmpty()); assertEquals(0, transportFactories.get())
    }
    @Test fun guestActualReplyRouteOpensLoginWithoutWrite() {
        accounts.store.useGuest()
        showRoute("replies:900", ExplicitLikeState.NOT_LIKED)
        compose.onNodeWithTag("reply-like:123").assertIsEnabled().performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("密码").fetchSemanticsNodes().isNotEmpty() }
        assertTrue(requests.isEmpty()); assertEquals(0, transportFactories.get())
    }
}
