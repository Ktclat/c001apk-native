// SPDX-License-Identifier: AGPL-3.0-only
package io.github.ktclat.c001apk.nativeapp.social

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.*
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.github.ktclat.c001apk.nativeapp.account.*
import io.github.ktclat.c001apk.nativeapp.data.*
import io.github.ktclat.c001apk.nativeapp.domain.*
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.ResponseBody.Companion.toResponseBody
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Production notice state/repository with socket-free synthetic responses and typed proof callbacks. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@OptIn(ExperimentalTestApi::class, ExperimentalCoroutinesApi::class)
class NoticeReadVerificationLifecycleTest {
    // IO completion must enqueue effects on the rule's scheduler. Compose 1.7.6's default
    // unconfined dispatcher can resume test frame/layout work inline on the IO worker.
    @get:Rule val compose = createComposeRule(effectContext = StandardTestDispatcher())
    private lateinit var store: AccountStore
    private lateinit var repository: SocialRepository
    private lateinit var client: OkHttpClient
    private val requests = CopyOnWriteArrayList<Request>()
    private var response: (Request) -> String = { challenge }
    private val challenge = """{"messageStatus":"err_request_captcha_v2","messageExtra":{"captchaId":"abcdefghijklmnop","captchaField":""}}"""
    private val proof = "NEC:abcdefgh:synthetic-notice-lifecycle-proof"
    private val activePages = CopyOnWriteArrayList<NotificationPageState>()
    private class Owner : LifecycleOwner {
        val registry = LifecycleRegistry.createUnsafe(this)
        override val lifecycle: Lifecycle get() = registry
    }
    private val owner = Owner()
    @Before fun setup() {
        store = AccountStore(object : CredentialPersistence {
            override fun read(): ByteArray? = null
            override fun write(bytes: ByteArray) = Unit
            override fun reset() = Unit
        }, "synthetic-notice-lifecycle-device")
        val origin = "https://api.coolapk.com/".toHttpUrl()
        val cookies = listOf("uid=123", "token=synthetic-owner", "SESSID=synthetic-original").map { Cookie.parse(origin, "$it; Domain=coolapk.com; Path=/; Secure")!! }
        check(store.saveAuthenticated("123", "Synthetic", "synthetic-notice-lifecycle-device", cookies, store.snapshot()))
        client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            check(request.method == "GET" && request.url.host == "api.coolapk.com" && request.url.encodedPath.startsWith("/v6/notification/"))
            requests += request
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("synthetic").body(response(request).toResponseBody()).build()
        }.build()
        repository = SocialRepository(store) { snapshot -> CoolapkHttpTransport(client = client, requestSession = { store.requestSessionFor(snapshot) }) }
        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.RESUMED }
    }
    @After fun close() {
        if (::client.isInitialized) { client.dispatcher.cancelAll(); client.connectionPool.evictAll(); client.dispatcher.executorService.shutdownNow() }
    }
    private fun rows(id: Int) = """{"data":[{"id":$id,"entityType":"feed","uid":44,"username":"Synthetic","message":"ROW_$id"}]}"""
    private fun awaitTag(tag: String) = awaitState(tag) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
    private fun awaitState(tag: String, condition: () -> Boolean) {
        try { compose.waitUntil(10_000) {
            // Flush the Compose/Main observation boundary before inspecting non-snapshot fixture
            // records. A wall-clock-only predicate can observe the previous committed composition.
            compose.onRoot(useUnmergedTree = true).fetchSemanticsNode()
            condition()
        } }
        catch (failure: Throwable) {
            println("NOTICE_LIFECYCLE_DIAGNOSTIC tag=$tag requests=${requests.map { it.url.encodedPath }} generation=${store.state.value.generation} lifecycle=${owner.lifecycle.currentState}")
            activePages.forEach { println("PAGE identity=${System.identityHashCode(it)} alive=${it.alive} epoch=${it.epoch} loading=${it.loading} loaded=${it.loaded} failure=${it.failure?.kind} pending=${it.failure?.noticeRead?.state?.value}") }
            println(compose.onRoot(useUnmergedTree = true).printToString())
            throw failure
        }
    }
    @Test fun asyncNoticeCompletionReturnsToComposeEffectScheduler() {
        val uiThread = compose.runOnIdle { Thread.currentThread() }
        val transportThreads = CopyOnWriteArrayList<Thread>()
        val effectThreads = CopyOnWriteArrayList<Thread>()
        val generation = store.state.value.generation
        var result: ApiResult<Page<SocialRow>>? = null
        var completed by mutableStateOf(false)
        // Observe the actual async OkHttp producer, not a withContext(IO) block that Compose's
        // test-only ApplyingContinuationInterceptor may itself intercept onto the test scheduler.
        response = { transportThreads += Thread.currentThread(); rows(10) }
        compose.setContent {
            LaunchedEffect(Unit) {
                effectThreads += Thread.currentThread()
                result = repository.notices(NoticeCategory.MENTIONS, PageCursor(), generation)
                effectThreads += Thread.currentThread()
                completed = true
            }
            if (completed) Text("NOTICE_COMPLETION_RETURNED")
        }
        awaitState("notice-effect-return") {
            compose.onAllNodesWithText("NOTICE_COMPLETION_RETURNED").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("NOTICE_COMPLETION_RETURNED").assertIsDisplayed()
        assertEquals(1, transportThreads.size); assertEquals(2, effectThreads.size)
        println("NOTICE_EFFECT_THREAD_WITNESS transport=${transportThreads.single().name}/${transportThreads.single().id} " +
            "before=${effectThreads.first().name}/${effectThreads.first().id} " +
            "after=${effectThreads.last().name}/${effectThreads.last().id} ui=${uiThread.name}/${uiThread.id} " +
            "transportOffUi=${transportThreads.single() !== uiThread} effectOnUi=${effectThreads.all { it === uiThread }}")
        assertNotSame("The transport must still complete on its actual worker", uiThread, transportThreads.single())
        assertSame("Effects must start on the Compose UI owner", uiThread, effectThreads.first())
        assertSame("Notice completion must return to the Compose UI owner", uiThread, effectThreads.last())
        compose.runOnIdle {
            assertEquals(listOf("10"), (result as ApiResult.Success).value.items.map { it.id })
            assertEquals(1, requests.size)
            assertEquals("/v6/notification/atMeList", requests.single().url.encodedPath)
            assertEquals("1", requests.single().url.queryParameter("page"))
        }
    }
    @Test fun failedRefreshRetainsRowsAndVerifiedSuccessReplacesExactlyOnce() = mode(false)
    @Test fun failedNextPageRetainsCursorAndVerifiedSuccessAppendsExactlyOnce() = mode(true)
    @Test fun genericVerifiedRefreshRetainsRowsAndReplacesExactlyOnce() = mode(false, true)
    @Test fun genericVerifiedNextPageRetainsCursorAndAppendsExactlyOnce() = mode(true, true)
    @Test fun delayedInitialResponseEnablesRefreshBeforeVerifiedGenericRetry() = mode(false, true, true)
    private fun mode(more: Boolean, genericRetry: Boolean = false, gateInitialResponse: Boolean = false) {
        val generation = store.state.value.generation
        val initialRelease = CountDownLatch(if (gateInitialResponse) 1 else 0)
        val release = CountDownLatch(1)
        response = { request -> when {
            requests.size == 1 -> { check(initialRelease.await(10, TimeUnit.SECONDS)); rows(10) }
            request.header("Cookie").orEmpty().contains("validate=") -> { check(release.await(10, TimeUnit.SECONDS)); rows(20) }
            else -> challenge
        } }
        compose.setContent { CompositionLocalProvider(LocalLifecycleOwner provides owner) { MaterialTheme {
            val state = remember { NotificationPageState().also { activePages += it } }
            NotificationListScreen(repository, generation, NoticeCategory.MENTIONS, state, {}, { _, _ -> })
        } } }
        try {
            if (gateInitialResponse) {
                // Also exercise completion after the initial loading/disabled frame commits.
                // The four existing modes retain their original immediate response schedule.
                awaitState("initial-loading") {
                    requests.size == 1 && activePages.singleOrNull()?.loading == true &&
                        compose.onAllNodesWithTag("notification-loading").fetchSemanticsNodes().isNotEmpty()
                }
                compose.onNodeWithText("刷新").assertIsNotEnabled()
                compose.runOnIdle { initialRelease.countDown() }
            }
            // CI58 reached loaded rows with no second GET and disabled Refresh at timeout.
            // Establish actual control readiness, not just repository state, before clicking.
            val initialAction = if (more) "加载更多" else "刷新"
            awaitState("initial-action-ready:$initialAction") {
                val page = activePages.singleOrNull()
                val action = compose.onAllNodesWithText(initialAction).fetchSemanticsNodes().singleOrNull()
                page?.loaded == true && !page.loading && page.rows.map { it.id } == listOf("10") &&
                    action != null && !action.config.contains(SemanticsProperties.Disabled)
            }
            compose.runOnIdle {
                assertEquals(1, requests.size)
                assertEquals(PageCursor(2, "10", "10"), activePages.single().next)
                assertNull(activePages.single().failure)
            }
            compose.onNodeWithText(initialAction).performScrollTo().assertIsDisplayed().assertIsEnabled().performClick()
            awaitTag("notice-human-verify")
            val page = activePages.single(); val cap = requireNotNull(page.failure?.noticeRead)
            compose.runOnIdle {
                assertEquals(listOf("10"), page.rows.map { it.id }); assertEquals(PageCursor(2, "10", "10"), page.next)
                assertEquals(more, page.failedMore); assertTrue(cap.acceptHumanProof(proof))
            }
            awaitTag("notice-verified-retry")
            val click = (if (genericRetry) compose.onNodeWithText("重试") else compose.onNodeWithTag("notice-verified-retry")).fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
            compose.runOnIdle { click(); click() }; compose.waitForIdle()
            compose.waitUntil(10_000) { requests.size == 3 }
            compose.runOnIdle { assertEquals(listOf("10"), page.rows.map { it.id }); release.countDown() }
            compose.waitUntil(10_000) { page.rows.lastOrNull()?.id == "20" }
            compose.runOnIdle { assertEquals(if (more) listOf("10", "20") else listOf("20"), page.rows.map { it.id }); assertNull(page.failure) }
            assertEquals(requests[1].url.encodedPath, requests[2].url.encodedPath)
            assertEquals(if (more) "2" else "1", requests[2].url.queryParameter("page"))
            assertEquals(requests[1].url.encodedQuery, requests[2].url.encodedQuery); assertEquals(3, requests.size)
        } finally { initialRelease.countDown(); release.countDown() }
    }
    @Test fun pauseAndRouteDisposalCloseVerifiedProofWithoutAutomaticResumeRead() {
        val generation = store.state.value.generation
        var shown by mutableStateOf(true)
        compose.setContent { CompositionLocalProvider(LocalLifecycleOwner provides owner) { MaterialTheme {
            if (shown) {
                val state = remember { NotificationPageState().also { activePages += it } }
                NotificationListScreen(repository, generation, NoticeCategory.MENTIONS, state, {}, { _, _ -> })
            }
        } } }
        awaitTag("notice-human-verify"); val first = requireNotNull(activePages.single().failure?.noticeRead)
        compose.runOnIdle { assertTrue(first.acceptHumanProof(proof)); owner.registry.currentState = Lifecycle.State.STARTED }
        compose.runOnIdle { assertEquals(DetailVerificationState.CLOSED, first.state.value); owner.registry.currentState = Lifecycle.State.RESUMED }
        compose.waitForIdle(); assertEquals(1, requests.size)
        compose.onNodeWithText("重试").performScrollTo().performClick(); awaitTag("notice-human-verify")
        val second = requireNotNull(activePages.single().failure?.noticeRead)
        compose.runOnIdle { shown = false }
        compose.runOnIdle { assertEquals(DetailVerificationState.CLOSED, second.state.value); assertFalse(second.acceptHumanProof(proof)) }
        assertEquals(2, requests.size)
    }
    @Test fun categoryAndSameUidGenerationReplacementCannotResurrectOldProof() {
        var category by mutableStateOf(NoticeCategory.MENTIONS)
        var generation by mutableLongStateOf(store.state.value.generation)
        var renderedCategory: NoticeCategory? = null
        var renderedGeneration: Long? = null
        compose.setContent { CompositionLocalProvider(LocalLifecycleOwner provides owner) { MaterialTheme {
            SideEffect { renderedCategory = category; renderedGeneration = generation }
            key(category, generation) {
                val state = remember { NotificationPageState().also { activePages += it } }
                NotificationListScreen(repository, generation, category, state, {}, { _, _ -> })
            }
        } } }
        awaitTag("notice-human-verify"); val first = requireNotNull(activePages.last().failure?.noticeRead)
        compose.runOnIdle { category = NoticeCategory.LIKES }
        awaitState("category-replaced") { renderedCategory == NoticeCategory.LIKES && activePages.size == 2 && activePages.last().failure?.noticeRead != null }
        val second = requireNotNull(activePages.last().failure?.noticeRead)
        compose.runOnIdle { assertEquals(DetailVerificationState.CLOSED, first.state.value); store.useGuest(); store.switchTo("123"); generation = store.state.value.generation }
        awaitState("generation-replaced") { renderedGeneration == generation && activePages.size == 3 && activePages.last().failure?.noticeRead != null }
        compose.runOnIdle { assertEquals(DetailVerificationState.CLOSED, second.state.value); assertFalse(first.acceptHumanProof(proof)); assertFalse(second.acceptHumanProof(proof)) }
        assertEquals(3, requests.size)
    }
}
