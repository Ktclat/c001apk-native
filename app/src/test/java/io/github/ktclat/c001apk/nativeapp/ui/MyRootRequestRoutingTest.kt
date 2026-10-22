// SPDX-License-Identifier: AGPL-3.0-only
package io.github.ktclat.c001apk.nativeapp.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import java.io.File
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import io.github.ktclat.c001apk.nativeapp.AppContainer
import io.github.ktclat.c001apk.nativeapp.account.*
import io.github.ktclat.c001apk.nativeapp.data.*
import io.github.ktclat.c001apk.nativeapp.local.*
import io.github.ktclat.c001apk.nativeapp.settings.*
import io.github.ktclat.c001apk.nativeapp.social.SocialRepository
import kotlinx.coroutines.flow.MutableStateFlow
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Real NativeApp navigation -> account-scoped production repositories -> intercepted wire request.
 * All credentials and rows are synthetic. Neither interceptor ever calls chain.proceed.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w393dp-h852dp-420dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MyRootRequestRoutingTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val requests = CopyOnWriteArrayList<Request>()
    private val unexpected = CopyOnWriteArrayList<String>()
    private val clients = mutableListOf<OkHttpClient>()
    private lateinit var accounts: AccountRuntime
    private lateinit var container: AppContainer
    @Volatile private var delayedFirstCollection: CountDownLatch? = null
    private val firstCollectionReturned = CountDownLatch(1)
    private val notices = listOf(
        "@我的动态" to "/v6/notification/atMeList",
        "@我的评论" to "/v6/notification/atCommentMeList",
        "我收到的赞" to "/v6/notification/feedLikeList",
        "好友关注" to "/v6/notification/contactsFollowList",
        "私信与信息" to "/v6/message/list",
    )
    private val personal = listOf(
        "关注" to "/v6/user/followList", "粉丝" to "/v6/user/fansList",
        "我的常去" to "/v6/user/recentHistoryList", "我的收藏" to "/v6/collection/list",
        "我的赞" to "/v6/user/likeList", "我的回复" to "/v6/user/replyList",
    )
    private class MemoryLocal : JsonStateStore<LocalState> {
        override val data = MutableStateFlow(LocalState())
        override suspend fun update(transform: (LocalState) -> LocalState) {
            data.value = LocalRepository.validateState(transform(data.value))
        }
    }

    @Before fun setup() {
        container = AppContainer(compose.activity.application)
        container.nativeUpdates.enabled = false // Suppress the unrelated real release-check client.
        AppContainer::class.java.getDeclaredField("local").apply { isAccessible = true }
            .set(container, LocalRepository(MemoryLocal()))
        val store = AccountStore(object : CredentialPersistence {
            private var saved: ByteArray? = null
            override fun read() = saved?.copyOf()
            override fun write(bytes: ByteArray) { saved = bytes.copyOf() }
            override fun reset() { saved = null }
        })
        for (id in listOf("1", "2")) {
            val cookie = Cookie.Builder().name("token").value("synthetic-account-$id")
                .domain("coolapk.com").path("/").secure()
                .expiresAt(System.currentTimeMillis() + 3_600_000).build()
            assertTrue(store.saveAuthenticated(id, "synthetic user $id", "synthetic-device-$id", listOf(cookie), store.snapshot()))
        }
        store.switchTo("1")
        val allowed = (notices + personal).map { it.second }.toSet() + "/v6/main/indexV8"
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            if (request.url.host !in setOf("api.coolapk.com", "api2.coolapk.com") || request.url.encodedPath !in allowed || request.method != "GET") {
                unexpected += "${request.method} ${request.url.encodedPath}"
                throw IOException("Unexpected synthetic route; sockets are disabled")
            }
            requests += request
            var body = """{"data":[]}"""
            if (request.url.encodedPath == "/v6/collection/list" && delayedFirstCollection != null) {
                val first = request.header("Cookie").orEmpty().contains("synthetic-account-1")
                if (first && delayedFirstCollection?.await(10, TimeUnit.SECONDS) == false) throw IOException("Synthetic gate timed out")
                val label = if (first) "stale-account-one" else "fresh-account-two"
                body = """{"data":[{"entityType":"collection","id":"55","title":"$label"}]}"""
                if (first) firstCollectionReturned.countDown()
            }
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("synthetic")
                .body(body.toResponseBody()).build()
            // No chain.proceed: even official URL/cookie-policy checks never reach DNS or sockets.
        }.build()
        val loginClient = OkHttpClient.Builder().addInterceptor {
            // Login preparation is a real root side-effect but must remain offline and never submit credentials.
            if (it.request().method != "GET") unexpected += "Unexpected login write"
            throw IOException("Synthetic login fixture: no external transport")
        }.build()
        clients += listOf(client, loginClient)
        val repository = HttpCoolapkRepository(client = client, requestSession = { store.captureRequestSession() })
        val social = SocialRepository(store) { snapshot -> CoolapkHttpTransport(client = client, requestSession = { store.requestSessionFor(snapshot) }) }
        val login = HttpLoginTransport(loginClient)
        val ctor = AccountRuntime::class.java.getDeclaredConstructor(AccountStore::class.java,
            HttpLoginTransport::class.java, LoginCoordinator::class.java, HttpCoolapkRepository::class.java,
            SocialRepository::class.java).apply { isAccessible = true }
        accounts = ctor.newInstance(store, login, LoginCoordinator(store, login, "synthetic-device"), repository, social)
    }
    @After fun cleanup() {
        delayedFirstCollection?.countDown()
        clients.forEach { it.dispatcher.cancelAll(); it.connectionPool.evictAll(); it.dispatcher.executorService.shutdownNow() }
        assertTrue("No unexpected request permitted: $unexpected", unexpected.isEmpty())
    }

    @Test fun fiveActualMyNoticeCardsSelectExactInitialWireCategory() {
        showMy()
        compose.onNodeWithText("synthetic user 1").assertIsDisplayed()
        capture("native-my-authenticated-393x852-synthetic-no-live-account.png")
        for ((label, path) in notices) {
            val before = requests.size
            clickMy(label)
            awaitRequest(path, before)
            assertAccount(requests.drop(before).single(), "1")
            assertEquals(path, requests.drop(before).single().url.encodedPath)
            backToMy()
        }
    }
    @Test fun sixActualMyPersonalShortcutsDispatchExactScopedQueries() {
        showMy()
        for ((label, path) in personal) {
            val before = requests.size
            clickMy(label)
            awaitRequest(path, before)
            compose.onNodeWithTag("personal-list").assertExists()
            val request = requests.drop(before).single()
            assertAccount(request, "1")
            assertEquals("1", request.url.queryParameter("page"))
            if (path == "/v6/collection/list") {
                assertNull(request.url.queryParameter("uid"))
                assertEquals("0", request.url.queryParameter("showDefault"))
            } else assertEquals("1", request.url.queryParameter("uid"))
            backToMy()
        }
    }
    @Test fun guestProtectedShortcutsOpenRealLoginWithoutAccountListRequests() {
        accounts.store.useGuest()
        showMy()
        // FOLLOW/FAN are intentionally absent for guests; the other9 protected cards remain.
        for (label in notices.map { it.first } + personal.drop(2).map { it.first }) {
            val before = requests.size
            clickMy(label)
            compose.onNodeWithText("登录酷安账号").assertExists()
            compose.waitForIdle()
            assertEquals(before, requests.size)
            backToMy()
        }
        compose.onNode(hasText("粉丝") and hasClickAction()).assertDoesNotExist()
    }
    @Test fun actualAccountToolbarSwitchChangesNextRequestScopeAndGuestModeGatesIt() {
        showMy()
        compose.onNodeWithContentDescription("账号管理").performClick()
        compose.onNode(hasText("切换") and isEnabled()).performScrollTo().performClick()
        compose.waitUntil(5_000) { accounts.store.state.value.activeId == "2" }
        backToMy()
        compose.onNodeWithText("synthetic user 2").assertExists()
        val before = requests.size
        clickMy("粉丝")
        awaitRequest("/v6/user/fansList", before)
        val request = requests.drop(before).single()
        assertAccount(request, "2")
        assertEquals("2", request.url.queryParameter("uid"))
        backToMy()
        compose.onNodeWithContentDescription("账号管理").performClick()
        compose.onNodeWithText("使用游客模式（保留账号）").performScrollTo().performClick()
        compose.waitUntil(5_000) { accounts.store.state.value.activeId == null }
        backToMy()
        val guestBefore = requests.size
        clickMy("我的收藏")
        compose.onNodeWithText("登录酷安账号").assertExists()
        assertEquals(guestBefore, requests.size)
        assertEquals(2, accounts.store.state.value.accounts.size)
    }
    @Test fun accountGenerationChangeDiscardsLatePersonalRowsInActualRoot() {
        delayedFirstCollection = CountDownLatch(1)
        showMy()
        val before = requests.size
        clickMy("我的收藏")
        awaitRequest("/v6/collection/list", before)
        // Simulate an external authenticated-account change while this actual route is loading.
        compose.runOnIdle { accounts.store.switchTo("2") }
        compose.waitUntil(5_000) { requests.drop(before).count { it.url.encodedPath == "/v6/collection/list" } == 2 }
        compose.waitUntil(5_000) { compose.onAllNodesWithText("fresh-account-two").fetchSemanticsNodes().isNotEmpty() }
        delayedFirstCollection!!.countDown()
        compose.waitUntil(5_000) { firstCollectionReturned.count == 0L }
        compose.waitForIdle()
        compose.onNodeWithText("stale-account-one").assertDoesNotExist()
        compose.onNodeWithText("fresh-account-two").assertExists()
        val scoped = requests.drop(before)
        assertEquals(2, scoped.size)
        assertAccount(scoped[0], "1"); assertAccount(scoped[1], "2")
        compose.onNodeWithText("fresh-account-two").assertIsDisplayed()
        capture("native-collection-populated-393x852-synthetic-no-live-account.png")
    }
    private fun showMy() {
        val model = BrowseViewModel(accounts.repository)
        compose.setContent {
            val state by accounts.store.state.collectAsState()
            val density = LocalDensity.current.density
            CompositionLocalProvider(
                LocalDensity provides Density(density, 1f),
                LocalReadingStyles provides readingStyles(ReadingPreset.COMFORTABLE, FontChoice.NOTO_SANS),
            ) {
                MaterialTheme(colorScheme = fixedColorScheme(0, false), typography = appTypography(FontChoice.NOTO_SANS)) {
                    NativeApp(model, container, AppSettings(dynamicColor = false), accounts, state)
                }
            }
        }
        // Synchronize the actual Compose root while awaiting the real asynchronous Home request.
        // A raw StateFlow-only poll does not perform the semantics/idle synchronization used by
        // every subsequent UI interaction. Keep the original state predicate and 5000ms bound.
        try {
            compose.waitUntil(5_000) {
                compose.onRoot(useUnmergedTree = true).fetchSemanticsNode()
                model.states.value["home"]?.loading == false
            }
        } catch (failure: ComposeTimeoutException) {
            // Observe, then rethrow the ORIGINAL failure; never rescue/retry/extend this gate.
            val rootState = model.states.value.mapValues { (_, state) ->
                "loading=${state.loading}, loaded=${state.loaded}, rows=${state.items.size}, error=${state.error}"
            }
            val lifecycle = compose.activity.lifecycle.currentState
            val safePaths = requests.map { "${it.method} ${it.url.encodedPath}" }
            val notes = StringBuilder("lifecycle=$lifecycle\nstates=$rootState\npaths=$safePaths\nunexpected=$unexpected\n")
            runCatching { notes.append(compose.onRoot(useUnmergedTree = true).printToString()) }
                .onFailure { notes.append("semantics observation failed: ${it.javaClass.name}\n") }
            runCatching { File("build/verification/my-root-readiness-failure-synthetic.txt").apply { parentFile?.mkdirs() }.writeText(notes.toString()) }
            throw failure
        }
        compose.onNode(hasText("我的") and hasClickAction()).performClick()
        compose.onNodeWithTag("my-root-list").assertExists()
        compose.waitForIdle()
    }
    /** Actual Compose Android view, native graphics; never HTML or a redrawn mock screen. */
    private fun capture(name: String) {
        compose.waitForIdle()
        val bitmap = compose.runOnUiThread {
            val view = compose.activity.findViewById<View>(android.R.id.content)
            check(view.width > 0 && view.height > 0)
            Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888).also { view.draw(Canvas(it)) }
        }
        val file = File("build/verification/$name").apply { parentFile?.mkdirs() }
        try {
            file.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        } finally { bitmap.recycle() }
    }
    private fun clickMy(label: String) {
        compose.onNode(hasText(label) and hasClickAction() and hasAnyAncestor(hasTestTag("my-root-list")))
            .performScrollTo().performClick()
    }
    private fun backToMy() {
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.onNodeWithTag("my-root-list").assertExists()
    }
    private fun awaitRequest(path: String, after: Int) {
        compose.waitUntil(5_000) { requests.drop(after).any { it.url.encodedPath == path } }
        compose.waitForIdle()
    }
    private fun assertAccount(request: Request, id: String) {
        assertEquals("GET", request.method)
        val cookie = request.header("Cookie").orEmpty()
        assertTrue("Synthetic account $id must own this request", cookie.contains("token=synthetic-account-$id"))
        assertFalse("Other synthetic account must not leak", cookie.contains("synthetic-account-${if (id == "1") "2" else "1"}"))
    }
}
