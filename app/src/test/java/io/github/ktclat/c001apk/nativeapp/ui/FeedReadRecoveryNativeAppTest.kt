// SPDX-License-Identifier: AGPL-3.0-only
package io.github.ktclat.c001apk.nativeapp.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.ktclat.c001apk.nativeapp.AppContainer
import io.github.ktclat.c001apk.nativeapp.account.*
import io.github.ktclat.c001apk.nativeapp.data.*
import io.github.ktclat.c001apk.nativeapp.domain.*
import io.github.ktclat.c001apk.nativeapp.feature.feed.SyntheticFeedImageLoader
import io.github.ktclat.c001apk.nativeapp.feature.media.*
import io.github.ktclat.c001apk.nativeapp.local.*
import io.github.ktclat.c001apk.nativeapp.settings.*
import io.github.ktclat.c001apk.nativeapp.social.SocialRepository
import kotlinx.coroutines.flow.MutableStateFlow
import okhttp3.*
import okhttp3.mockwebserver.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Real NativeApp route, HTTP/decoder/readers, loopback fixture servers, offline image bytes.
 * These tests prove the supported response contract, not availability of the live Coolapk service.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w393dp-h852dp-420dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FeedReadRecoveryNativeAppTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val owner = SessionViewModelStore()
    private lateinit var api: MockWebServer
    private lateinit var web: MockWebServer
    private lateinit var model: BrowseViewModel
    private lateinit var images: SyntheticFeedImageLoader
    private lateinit var client: OkHttpClient
    private val requests = CopyOnWriteArrayList<RecordedRequest>()
    private val imageUrls = CopyOnWriteArrayList<String>()
    private val publicEntered = CountDownLatch(1)
    private val publicRelease = CountDownLatch(1)
    private val publicTerminal = CountDownLatch(1)
    private var holdPublic = false
    private var publicHtml = false
    private var authenticated = false
    private var verifiedMissingBody = false
    private var apiAllowsDetail = false
    private val challenge = """{"messageStatus":"err_request_captcha_v2","message":"captcha","messageExtra":{"captchaId":"abcdefghijklmnop","captchaField":"_v2_post_token"}}"""
    private class MemoryLocal : JsonStateStore<LocalState> {
        override val data = MutableStateFlow(LocalState())
        override suspend fun update(transform: (LocalState) -> LocalState) { data.value = transform(data.value) }
    }
    private fun fixture(name: String): String = requireNotNull(javaClass.getResource("/feed-reading/$name.json")).readText()
        .replace("https://image.coolapk.com/synthetic-author.jpg", SyntheticFeedImageLoader.AVATAR)
        .replace("https://image.coolapk.com/synthetic-feed@640x480.jpg", SyntheticFeedImageLoader.LANDSCAPE)
    @Before fun setup() {
        images = SyntheticFeedImageLoader(compose.activity)
        api = MockWebServer(); web = MockWebServer()
        api.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests += request
                assertEquals("GET", request.method)
                return when (request.requestUrl!!.encodedPath) {
                    "/v6/main/indexV8" -> MockResponse().setBody(fixture("home"))
                    "/v6/feed/detail" -> if (apiAllowsDetail) MockResponse().setBody(fixture("ordinary-detail"))
                        else if (request.requestUrl!!.queryParameter("_v2_post_token") == null) MockResponse().setBody(challenge)
                        else MockResponse().setBody(if (verifiedMissingBody) """{"data":{"id":42001,"entityType":"feed","description":"NOT_BODY"}}""" else fixture("ordinary-detail"))
                    "/v6/feed/replyList" -> MockResponse().setBody(fixture("comments"))
                    else -> throw AssertionError("Unexpected loopback route ${request.path}")
                }
            }
        }
        web.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests += request; assertEquals("GET", request.method); assertNull(request.getHeader("Cookie"))
                assertTrue(request.path in listOf("/feed/42001", "/feed/42002"))
                publicEntered.countDown(); if (holdPublic) check(publicRelease.await(15, TimeUnit.SECONDS))
                return MockResponse().setBody(if (publicHtml) "<html>请用酷安手机APP扫码查看详情</html>" else fixture(if (request.path == "/feed/42002") "article-detail" else "ordinary-detail"))
            }
        }
        api.start(); web.start()
    }
    @After fun close() {
        publicRelease.countDown(); owner.viewModelStore.clear()
        if (::client.isInitialized) { client.dispatcher.cancelAll(); client.connectionPool.evictAll(); client.dispatcher.executorService.shutdownNow() }
        api.shutdown(); web.shutdown(); images.close()
        assertTrue(images.unexpectedUrls.toString(), images.unexpectedUrls.isEmpty())
    }
    private fun show(scale: Float = 1f, dark: Boolean = false, route: String? = null) {
        val container = AppContainer(compose.activity.application).also { it.nativeUpdates.enabled = false }
        AppContainer::class.java.getDeclaredField("local").apply { isAccessible = true }.set(container, LocalRepository(MemoryLocal()))
        val store = AccountStore(object : CredentialPersistence {
            override fun read(): ByteArray? = null
            override fun write(bytes: ByteArray) = Unit
            override fun reset() = Unit
        }, guestDeviceId = "synthetic-read-device")
        if (authenticated) {
            val cookie = Cookie.Builder().name("token").value("synthetic").domain("coolapk.com").path("/").secure().expiresAt(System.currentTimeMillis()+3600000).build()
            assertTrue(store.saveAuthenticated("901", "合成账号", "synthetic-read-device", listOf(cookie), store.snapshot()))
        }
        client = OkHttpClient.Builder().eventListener(object : EventListener() {
            override fun callEnd(call: Call) { if (call.request().url.encodedPath.startsWith("/feed/")) publicTerminal.countDown() }
            override fun callFailed(call: Call, ioe: java.io.IOException) { if (call.request().url.encodedPath.startsWith("/feed/")) publicTerminal.countDown() }
        }).build()
        val snapshot = store.snapshot()
        lateinit var session: RequestSession
        session = RequestSession({ if (authenticated) "token=synthetic; SESSID=synthetic" else null }, { store.isCurrent(snapshot) }, { _, _ -> },
            deviceCode = "synthetic-read-device", isGuest = !authenticated, isSessionCurrent = { store.isCurrent(snapshot) }, acceptChallengeCookies = { _, _ -> session })
        val repository = HttpCoolapkRepository(client, ApiEndpoints(api.url("/"), api.url("/"), web.url("/")),
            ClientProtocol("synthetic-read-device", { 1770000000 }), requestSession = { session })
        val login = HttpLoginTransport(client)
        val social = SocialRepository(store) { CoolapkHttpTransport(client, ApiEndpoints(api.url("/"), api.url("/")), requestSession = { session }) }
        val constructor = AccountRuntime::class.java.getDeclaredConstructor(AccountStore::class.java, HttpLoginTransport::class.java,
            LoginCoordinator::class.java, HttpCoolapkRepository::class.java, SocialRepository::class.java).apply { isAccessible = true }
        val accounts = constructor.newInstance(store, login, LoginCoordinator(store, login, "synthetic"), repository, social)
        val articleImages = ArticleImageLoader { url -> imageUrls += url; ArticleImageResult.Ready(Bitmap.createBitmap(64, 36, Bitmap.Config.ARGB_8888).apply { eraseColor(0xff338899.toInt()) }) }
        compose.setContent {
            val state by store.state.collectAsState(); owner.select(state.generation)
            val currentModel = remember(state.generation) { ViewModelProvider(owner, viewModelFactory { initializer { BrowseViewModel(repository) } })[BrowseViewModel::class.java] }
            SideEffect { model = currentModel }
            CompositionLocalProvider(LocalViewModelStoreOwner provides owner, LocalMediaImageLoader provides images.loader,
                LocalArticleImageLoader provides articleImages, LocalDensity provides Density(LocalDensity.current.density, scale),
                LocalReadingStyles provides readingStyles(ReadingPreset.COMFORTABLE, FontChoice.NOTO_SANS)) {
                MaterialTheme(colorScheme = fixedColorScheme(0, dark), typography = appTypography(FontChoice.NOTO_SANS)) {
                    key(state.generation) { NativeApp(currentModel, container, AppSettings(floatingNavigation = true, dynamicColor = false), accounts, state, initialRoute = route) }
                }
            }
        }
    }
    private fun awaitTag(tag: String) = compose.waitUntil(15_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
    private fun capture(name: String) {
        compose.waitForIdle()
        val bitmap = compose.runOnUiThread {
            val view = compose.activity.findViewById<View>(android.R.id.content)
            Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888).also { view.draw(Canvas(it)) }
        }
        File("build/verification/feed-recovery/$name.png").apply { parentFile!!.mkdirs() }.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
    }
    @Test fun normalListToPublicDetailReadsCompleteTailPhotosAndExplicitComments() = ordinary(1f, false, "normal")
    @Test fun largeFontListToDetailRetainsCompleteBody() = ordinary(1.6f, false, "large")
    @Test fun darkListToDetailRetainsCompleteBody() = ordinary(1f, true, "dark")
    private fun ordinary(scale: Float, dark: Boolean, name: String) {
        show(scale, dark); awaitTag("feed-body:42001")
        compose.onNodeWithTag("feed-body:42001").performClick(); awaitTag("full-post-page")
        assertTrue(model.states.value.getValue("feed:42001").items.single().publicRead)
        assertEquals(0, requests.count { it.requestUrl!!.encodedPath == "/v6/feed/replyList" })
        compose.onNodeWithTag("full-post-page").assertTextContains("FULL_BODY_BEGIN", substring = true)
        capture("$name-first")
        repeat(3) { compose.onNodeWithText("下一段").performScrollTo().performClick() }
        compose.onNodeWithTag("full-post-page").assertTextContains("REAL_DETAIL_COMPLETE_SUFFIX", substring = true)
        compose.onNodeWithTag("full-post-page").performScrollTo().assertIsDisplayed(); capture("$name-tail")
        compose.onNodeWithTag("media-cell-0").performScrollTo()
        compose.waitUntil(10_000) { SyntheticFeedImageLoader.LANDSCAPE in images.loadedUrls }
        capture("$name-photo-before-assert")
        compose.onNodeWithTag("media-cell-0").assertIsDisplayed()
        compose.onNodeWithTag("media-grid-content-0", useUnmergedTree = true).assertIsDisplayed(); capture("$name-photo")
        compose.onNodeWithTag("feed-detail-list").performScrollToNode(hasTestTag("detail-load-comments"))
        compose.onNodeWithTag("detail-load-comments").assertIsDisplayed().performClick()
        compose.waitUntil(10_000) { compose.onRoot().fetchSemanticsNode(); model.states.value["ordered:42001:RECENT_REPLY:false"]?.loaded == true }
        compose.onNodeWithTag("feed-detail-list").performScrollToIndex(4)
        compose.onNode(hasText("COMMENT_API_SUFFIX", substring = true)).assertIsDisplayed()
        assertEquals(1, requests.count { it.requestUrl!!.encodedPath == "/v6/feed/replyList" })
        compose.onNodeWithContentDescription("返回").performClick(); awaitTag("feed-body:42001")
        assertEquals(1, requests.count { it.requestUrl!!.encodedPath == "/v6/main/indexV8" })
        assertEquals(1, requests.count { it.requestUrl!!.encodedPath == "/v6/feed/detail" })
    }
    @Test fun structuredPublicDetailKeepsTextImageShareAndCompleteSuffix() {
        show(route = "feed:42002"); awaitTag("article-page")
        val body = model.states.value.getValue("feed:42002").items.single().fullPostBody as FullPostBody.Structured
        val prepared = prepareFullPost(body) as PreparedFullPost.Article
        capture("article-first")
        repeat(prepared.pages.size - 1) { compose.onNodeWithText("下一段").performScrollTo().performClick() }
        compose.onNode(hasText("ARTICLE_COMPLETE_SUFFIX", substring = true)).performScrollTo().assertIsDisplayed()
        assertTrue(imageUrls.any { "synthetic-article" in it }); compose.onNodeWithTag("article-share-2").assertExists()
        compose.onNodeWithTag("article-text-3", useUnmergedTree = true).performScrollTo()
        val tail = compose.onNodeWithTag("article-text-3", useUnmergedTree = true).getUnclippedBoundsInRoot()
        val viewport = compose.onNodeWithTag("feed-detail-list").getUnclippedBoundsInRoot()
        assertTrue("Final article text must be fully inside viewport: $tail / $viewport", tail.top >= viewport.top && tail.bottom <= viewport.bottom)
        capture("article-tail")
        compose.onAllNodes(hasText("ARTICLE_LIST_SUMMARY_NOT_FULL_BODY", substring = true)).assertCountEquals(0)
    }
    @Test fun explicitVerifiedRetryRunsOnceAndKeepsPublicBodyWhenVerifiedResponseIsInvalid() {
        authenticated = true; verifiedMissingBody = true; show(route = "feed:42001"); awaitTag("full-post-page")
        val original = model.states.value.getValue("feed:42001").items.single()
        val pending = requireNotNull(original.accountDetailRetry)
        compose.runOnIdle { assertTrue(pending.acceptHumanProof("NEC:abcdefgh:synthetic-proof")) }
        compose.onNodeWithTag("detail-verified-retry").performScrollTo().assertIsDisplayed()
        capture("verified-before-click")
        compose.onNodeWithTag("detail-verified-retry").performClick()
        try { awaitTag("feed-detail-error") }
        catch (problem: Throwable) {
            capture("verified-after-timeout")
            val state = model.states.value["feed:42001"]
            throw AssertionError("Verification state=${pending.state.value}; loading=${state?.loading}; error=${state?.error}; API calls=${requests.count { it.requestUrl!!.encodedPath == "/v6/feed/detail" }}; verified calls=${requests.count { it.requestUrl!!.queryParameter("_v2_post_token") != null }}", problem)
        }
        assertFalse(model.states.value.getValue("feed:42001").loading)
        assertNotNull(model.states.value.getValue("feed:42001").error)
        assertEquals(original.fullPostBody, model.states.value.getValue("feed:42001").items.single().fullPostBody)
        assertEquals(2, requests.count { it.requestUrl!!.encodedPath == "/v6/feed/detail" })
        compose.onNodeWithTag("feed-detail-error").performScrollTo().assertIsDisplayed(); capture("verified-invalid-keeps-body")
        compose.onNodeWithTag("detail-public-notice").performScrollTo().assertIsDisplayed()
            .assertTextEquals("保留上次读取的公开正文；最新详情未确认，账号点赞状态未获取。")
        capture("verified-invalid-stale-notice")
    }
    @Test fun blockedPublicPageShowsErrorAndLeavingCancelsVerificationCapability() {
        authenticated = true; publicHtml = true; show(route = "feed:42001"); awaitTag("detail-human-verify")
        val pending = requireNotNull(model.states.value.getValue("feed:42001").failure?.detailRead)
        compose.onNodeWithTag("feed-detail-error").assertIsDisplayed(); compose.onNodeWithTag("full-post-reader").assertDoesNotExist(); capture("public-unavailable-error")
        compose.onNodeWithContentDescription("返回").performClick(); awaitTag("content-list")
        assertEquals(DetailVerificationState.CLOSED, pending.state.value)
        assertEquals(0, requests.count { it.requestUrl!!.encodedPath == "/v6/feed/replyList" })
    }
    @Test fun leavingWhilePublicRequestIsPendingCannotCacheLateChallenge() {
        authenticated = true; publicHtml = true; holdPublic = true; show(route = "feed:42001")
        compose.waitUntil(10_000) { publicEntered.count == 0L }
        compose.onNodeWithContentDescription("返回").performClick(); awaitTag("content-list"); publicRelease.countDown()
        compose.waitUntil(10_000) { publicTerminal.count == 0L }
        compose.waitForIdle()
        val state = model.states.value["feed:42001"]
        assertFalse(state?.loading == true); assertNull(state?.failure?.detailRead); assertTrue(state?.items.orEmpty().isEmpty())
    }
    @Test fun successfulGuestApiDetailStillLoadsUsableCommentsWithoutArtificialDeviceGate() = apiSuccess(false)
    @Test fun successfulAccountApiDetailLoadsCommentsWithoutPublicRead() = apiSuccess(true)
    private fun apiSuccess(account: Boolean) {
        authenticated = account; apiAllowsDetail = true; show(route = "feed:42001"); awaitTag("full-post-page")
        compose.waitUntil(10_000) { compose.onRoot().fetchSemanticsNode(); model.states.value["ordered:42001:RECENT_REPLY:false"]?.loaded == true }
        assertFalse(model.states.value.getValue("feed:42001").items.single().publicRead)
        assertEquals(0, web.requestCount); assertEquals(1, requests.count { it.requestUrl!!.encodedPath == "/v6/feed/replyList" })
        compose.onNodeWithTag("detail-load-comments").assertDoesNotExist()
    }

}
