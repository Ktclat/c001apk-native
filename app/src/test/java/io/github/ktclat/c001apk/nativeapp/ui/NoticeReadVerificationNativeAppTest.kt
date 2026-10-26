// SPDX-License-Identifier: AGPL-3.0-only
package io.github.ktclat.c001apk.nativeapp.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.semantics.SemanticsActions
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
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.mockwebserver.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog
import java.io.File
import java.net.InetAddress
import java.util.concurrent.CopyOnWriteArrayList

/** Actual NativeApp and loopback transport; provider execution is disabled by ShadowWebView.
 * The typed synthetic bridge is UI wiring evidence, never live CAPTCHA/service acceptance. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w393dp-h852dp-420dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NoticeReadVerificationNativeAppTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private lateinit var server: MockWebServer
    private lateinit var client: OkHttpClient
    private val requests = CopyOnWriteArrayList<RecordedRequest>()
    private val blockedHosts = CopyOnWriteArrayList<String>()
    private var captureRoot = "build/verification/notice-read-after"
    private val target = "/v6/notification/atMeList"
    private val challenge = """{"messageStatus":"err_request_captcha_v2","messageExtra":{"captchaId":"abcdefghijklmnop","captchaField":""}}"""
    private class MemoryLocal : JsonStateStore<LocalState> {
        override val data = MutableStateFlow(LocalState())
        override suspend fun update(transform: (LocalState) -> LocalState) { data.value = transform(data.value) }
    }
    @After fun close() {
        if (::client.isInitialized) { client.dispatcher.cancelAll(); client.connectionPool.evictAll(); client.dispatcher.executorService.shutdownNow() }
        if (::server.isInitialized) server.shutdown()
        assertTrue("No non-loopback DNS targets", blockedHosts.isEmpty())
    }
    @Test fun actualMyShortcutHasTrustedHostCancellationAndSeparateOneShotRead() = actualRoot(false)
    @Test fun actualMyShortcutGenericRetryKeepsVerifiedProofAndRejectsStaleCallbacks() = actualRoot(true)
    private fun actualRoot(genericRetry: Boolean) {
        if (genericRetry) captureRoot = "build/verification/notice-read-generic-after"
        var persistenceWrites = 0
        server = MockWebServer()
        server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests += request; check(request.method == "GET")
                return when (request.requestUrl!!.encodedPath) {
                    target -> if (request.getHeader("Cookie").orEmpty().contains("validate=NEC%3Aabcdefgh%3Asynthetic-notice-ui-proof"))
                        MockResponse().setBody("""{"data":[{"id":8,"entityType":"notification","fromuid":44,"fromusername":"合成通知作者","note":"在<a href='/feed/900'>合成通知目标</a>中提到了你"},{"entityType":"card","entityTemplate":"noMoreDataCard"}]}""")
                        else MockResponse().setResponseCode(403).setBody(challenge).addHeader("Set-Cookie", "SESSID=synthetic-notice-read; Path=/")
                            .addHeader("Set-Cookie", "uid=999; Path=/").addHeader("Set-Cookie", "token=foreign; Path=/")
                    "/v6/user/profile" -> MockResponse().setBody("""{"data":{"uid":"123","username":"合成账号","feed":0,"follow":0,"fans":0}}""")
                    "/v6/notification/checkCount" -> MockResponse().setBody("""{"data":{"atme":1,"atcommentme":0,"feedlike":0,"contacts_follow":0,"message":0,"notification":0}}""")
                    "/v6/feed/detail" -> MockResponse().setBody("""{"data":{"id":900,"entityType":"feed","uid":44,"username":"合成作者","message":"合成通知目标正文","message_raw_output":"[]","replynum":0}}""")
                    "/v6/main/indexV8", "/v6/feed/replyList" -> MockResponse().setBody("{\"data\":[]}")
                    else -> error("Unexpected synthetic path: ${request.requestUrl!!.encodedPath}")
                }
            }
        }
        server.start()
        client = OkHttpClient.Builder().dns(object : Dns { override fun lookup(hostname: String): List<InetAddress> {
            if (hostname !in setOf("localhost", "127.0.0.1", "::1")) { blockedHosts += hostname; throw java.net.UnknownHostException("External network denied") }
            return listOf(InetAddress.getByName(hostname))
        } }).build()
        val store = AccountStore(object : CredentialPersistence {
            override fun read(): ByteArray? = null
            override fun write(bytes: ByteArray) { persistenceWrites++ }
            override fun reset() = Unit
        }, "synthetic-notice-device")
        val origin = "https://api.coolapk.com/".toHttpUrl()
        val cookies = listOf("uid=123", "token=synthetic-owner", "SESSID=synthetic-original").map { Cookie.parse(origin, "$it; Domain=coolapk.com; Path=/; Secure")!! }
        check(store.saveAuthenticated("123", "合成账号", "synthetic-notice-device", cookies, store.snapshot()))
        val baselineWrites = persistenceWrites
        fun mapped(source: RequestSession): RequestSession = source.copy(cookieFor = { url ->
            check(url.scheme == "http" && url.host == server.hostName && url.port == server.port)
            source.cookieFor(url.newBuilder().scheme("https").host("api.coolapk.com").port(443).build())
        })
        val endpoints = ApiEndpoints(server.url("/"), server.url("/"), server.url("/"))
        val repository = HttpCoolapkRepository(client, endpoints, ClientProtocol("synthetic-notice-device", { 1770000000 }), requestSession = { mapped(store.captureRequestSession()) })
        val login = HttpLoginTransport(client)
        val social = SocialRepository(store) { snapshot -> CoolapkHttpTransport(client, endpoints, requestSession = { mapped(store.requestSessionFor(snapshot)) }) }
        val ctor = AccountRuntime::class.java.getDeclaredConstructor(AccountStore::class.java, HttpLoginTransport::class.java,
            LoginCoordinator::class.java, HttpCoolapkRepository::class.java, SocialRepository::class.java).apply { isAccessible = true }
        val accounts = ctor.newInstance(store, login, LoginCoordinator(store, login, "synthetic-notice-device"), repository, social)
        val container = AppContainer(compose.activity.application).also { it.nativeUpdates.enabled = false }
        AppContainer::class.java.getDeclaredField("local").apply { isAccessible = true }.set(container, LocalRepository(MemoryLocal()))
        val model = BrowseViewModel(repository)
        compose.setContent {
            val state by store.state.collectAsState()
            MaterialTheme(colorScheme = fixedColorScheme(0, false), typography = appTypography(FontChoice.NOTO_SANS)) {
                NativeApp(model, container, AppSettings(floatingNavigation = true, dynamicColor = false), accounts, state)
            }
        }
        compose.onNodeWithText("我的").performClick()
        compose.onNodeWithTag("my-notice-MENTIONS").performScrollTo().performClick()
        awaitTag("notice-human-verify"); capture("01-entry")
        compose.onNodeWithTag("notice-human-verify").performScrollTo().performClick()
        val first = host(); val oldBridge = bridge(first); capture("02-host", dialog = true)
        compose.onNodeWithText("取消验证").performClick()
        compose.onAllNodes(isDialog()).assertCountEquals(0)
        assertTrue(shadowOf(first).wasDestroyCalled()); assertNull(shadowOf(first).getJavascriptInterface("CaptchaProof"))
        val cancelledCount = readCount(); oldBridge(); compose.waitForIdle(); assertEquals(cancelledCount, readCount())
        compose.onNodeWithText("验证已关闭或失效，可重新加载内容。").performScrollTo().assertIsDisplayed(); capture("03-cancelled")
        val staleRetry = if (genericRetry) genericRetryClick() else null
        // The currently displayed CLOSED capability permits a fresh explicit read, not replay.
        compose.onNodeWithText("重试").performScrollTo().performClick()
        awaitTag("notice-human-verify")
        val currentRetry = if (genericRetry) genericRetryClick() else null
        if (genericRetry) {
            compose.runOnIdle { staleRetry!!() }; compose.waitForIdle()
            assertEquals(2, readCount())
            compose.onNodeWithTag("notice-human-verify").assertIsDisplayed()
        }
        compose.onNodeWithTag("notice-human-verify").performScrollTo().performClick()
        val second = host(); oldBridge(); compose.waitForIdle(); compose.onAllNodesWithTag("notice-verified-retry").assertCountEquals(0)
        val beforeProof = readCount(); bridge(second)(); awaitTag("notice-verified-retry")
        assertTrue(shadowOf(second).wasDestroyCalled()); assertEquals(beforeProof, readCount()); capture("04-explicit-retry-ready")
        if (genericRetry) {
            compose.runOnIdle { staleRetry!!() }; compose.waitForIdle()
            assertEquals(beforeProof, readCount())
            compose.onNodeWithTag("notice-verified-retry").assertIsDisplayed()
            // Invoke the real generic button callback captured before verification; it must
            // observe this same failure's now-VERIFIED capability, and be single-flight.
            compose.runOnIdle { currentRetry!!(); currentRetry() }; compose.waitForIdle()
        } else compose.onNodeWithTag("notice-verified-retry").performScrollTo().performClick()
        awaitTag("notice-open:notification:8:0"); capture("05-read-success")
        if (genericRetry) {
            compose.runOnIdle { currentRetry!!(); staleRetry!!() }; compose.waitForIdle()
        }
        assertEquals(beforeProof + 1, readCount()); assertEquals(3, readCount())
        assertEquals(baselineWrites, persistenceWrites)
        val verified = requests.single { it.requestUrl!!.encodedPath == target && it.getHeader("Cookie").orEmpty().contains("validate=") }
        assertEquals("$target?page=1", verified.path); assertEquals(0L, verified.bodySize)
        assertTrue(verified.getHeader("Cookie")!!.contains("uid=123")); assertFalse(verified.getHeader("Cookie")!!.contains("999")); assertFalse(verified.getHeader("Cookie")!!.contains("foreign"))
        compose.onNodeWithTag("notice-open:notification:8:0").performScrollTo().performClick()
        compose.waitUntil(10_000) { compose.onAllNodes(hasText("合成通知目标正文", substring = true)).fetchSemanticsNodes().isNotEmpty() }
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        awaitTag("notice-open:notification:8:0"); assertEquals(beforeProof + 1, readCount())
        assertTrue(requests.all { it.method == "GET" }); assertFalse(requests.any { it.requestUrl!!.encodedPath.contains("delete") })
        File("$captureRoot/provenance.txt").apply { parentFile!!.mkdirs() }.writeText(
            "Actual NativeApp My → notice list → trusted challenge host → cancel/late proof → new proof → separate explicit GET → feed/Back.\n" +
            "Synthetic account, loopback responses, ShadowWebView typed callback; provider SDK was not executed and no live CAPTCHA was solved.\n" +
            "retryControl=${if (genericRetry) "generic" else "primary"}; staleAndDuplicateCallbacks=${genericRetry}; noticeReads=${readCount()}; externalDNS=${blockedHosts.size}; persistenceWritesAfterLogin=${persistenceWrites-baselineWrites}\n")
    }
    private fun genericRetryClick() = compose.onNodeWithText("重试").fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
    private fun readCount() = requests.count { it.requestUrl!!.encodedPath == target }
    private fun awaitTag(tag: String) = compose.waitUntil(10_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
    private fun capture(name: String, dialog: Boolean = false) {
        compose.waitForIdle()
        val bitmap = compose.runOnUiThread {
            val view = if (dialog) {
                val shown = ShadowDialog.getShownDialogs().filter { it.isShowing }.distinct(); check(shown.size == 1)
                requireNotNull(shown.single().window).decorView
            } else compose.activity.findViewById<View>(android.R.id.content)
            check(view.width > 0 && view.height > 0)
            Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888).also { view.draw(Canvas(it)) }
        }
        File("$captureRoot/$name.png").apply { parentFile!!.mkdirs() }.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
    private fun views(view: View): List<View> = listOf(view) + if (view is ViewGroup) (0 until view.childCount).flatMap { views(view.getChildAt(it)) } else emptyList()
    private fun host(): WebView {
        compose.onNodeWithText("取消验证").assertIsDisplayed(); compose.waitForIdle()
        return compose.runOnUiThread {
            val shown = ShadowDialog.getShownDialogs().filter { it.isShowing }.distinct(); check(shown.size == 1)
            views(requireNotNull(shown.single().window).decorView).filterIsInstance<WebView>().single()
        }
    }
    private fun bridge(view: WebView): () -> Unit {
        val shadow = shadowOf(view)
        assertEquals(ChallengeOriginPolicy.PAGE, view.url); assertEquals(ChallengeOriginPolicy.PAGE, shadow.lastLoadDataWithBaseURL.historyUrl)
        val receiver = requireNotNull(shadow.getJavascriptInterface("CaptchaProof"))
        val nonce = Regex("CaptchaProof\\.verified\\('([a-f0-9]+)'", RegexOption.MULTILINE).find(shadow.lastLoadDataWithBaseURL.data)!!.groupValues[1]
        val method = receiver.javaClass.getDeclaredMethod("verified", String::class.java, String::class.java).apply { isAccessible = true }
        return { compose.runOnUiThread { method.invoke(receiver, nonce, "synthetic-notice-ui-proof") } }
    }
}
