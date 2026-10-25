// SPDX-License-Identifier: AGPL-3.0-only
package io.github.ktclat.c001apk.nativeapp.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import io.github.ktclat.c001apk.nativeapp.AppContainer
import io.github.ktclat.c001apk.nativeapp.account.*
import io.github.ktclat.c001apk.nativeapp.data.*
import io.github.ktclat.c001apk.nativeapp.domain.*
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

/** Actual NativeApp→HTTP→repository→VM→verification host→JS callback→explicit retry.
 * All API/share responses and proof callbacks are synthetic loopback fixtures. Robolectric's
 * ShadowWebView records real production loadData but does not run/download the provider SDK.
 * Screenshots prove entry, host, cancel/return/retry UI, NOT a real phone/service CAPTCHA pass.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w393dp-h852dp-420dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ReadVerificationNativeAppTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private lateinit var server: MockWebServer
    private lateinit var client: OkHttpClient
    private lateinit var model: BrowseViewModel
    private lateinit var store: AccountStore
    private val requests = CopyOnWriteArrayList<RecordedRequest>()
    private val blockedHosts = CopyOnWriteArrayList<String>()
    private val challenge = """{"messageStatus":"err_request_captcha_v2","messageExtra":{"captchaId":"abcdefghijklmnop","captchaField":""}}"""
    private class MemoryLocal : JsonStateStore<LocalState> {
        override val data = MutableStateFlow(LocalState())
        override suspend fun update(transform: (LocalState) -> LocalState) { data.value = transform(data.value) }
    }
    @After fun close() {
        if (::client.isInitialized) { client.dispatcher.cancelAll(); client.connectionPool.evictAll(); client.dispatcher.executorService.shutdownNow() }
        if (::server.isInitialized) server.shutdown()
        CookieManager.getInstance().removeAllCookies(null)
        assertTrue("No non-loopback DNS targets", blockedHosts.isEmpty())
    }
    @Test fun guestHomeEntryCancelReturnAndExplicitRetry() = exercise(false, false)
    @Test fun accountHomeEntryCancelReturnAndExplicitRetry() = exercise(true, false)
    @Test fun guestDetailEntryCancelReturnAndExplicitRetry() = exercise(false, true)
    @Test fun accountDetailEntryCancelReturnAndExplicitRetry() = exercise(true, true)
    @Test fun recompositionKeepsOpenProviderHostAndNonce() {
        val pending = PendingPageRead(ApiChallenge.Netease("abcdefghijklmnop", ""), { true },
            { error("No image") }, { error("No graphic validation") }, { error("No read retry") })
        var loading by mutableStateOf(false)
        compose.setContent { MaterialTheme { PageReadVerification(pending, loading) {} } }
        compose.onNodeWithTag("read-human-verify").performClick()
        val original = host(); val noncePage = shadowOf(original).lastLoadDataWithBaseURL.data
        compose.runOnIdle { loading = true }; compose.waitForIdle()
        assertSame(original, host()); assertEquals(noncePage, shadowOf(host()).lastLoadDataWithBaseURL.data)
        assertFalse(shadowOf(original).wasDestroyCalled())
        compose.runOnIdle { loading = false }; compose.waitForIdle(); assertSame(original, host())
        compose.onNodeWithText("取消验证").performClick(); compose.onAllNodes(isDialog()).assertCountEquals(0)
        assertTrue(shadowOf(original).wasDestroyCalled())
    }
    private fun exercise(authenticated: Boolean, detail: Boolean) {
        server = MockWebServer()
        server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests += request
                assertEquals("GET", request.method)
                val path = request.requestUrl!!.encodedPath
                if (path.startsWith("/feed/")) { assertNull(request.getHeader("Cookie")); return MockResponse().setResponseCode(404) }
                if (path == "/v6/feed/replyList") return MockResponse().setBody("{\"data\":[]}")
                check(path in setOf("/v6/main/indexV8", "/v6/feed/detail"))
                val proof = request.getHeader("Cookie").orEmpty().contains("validate=NEC%3Aabcdefgh%3Asynthetic-ui-proof")
                return if (!proof) MockResponse().setResponseCode(403).setBody(challenge)
                    .addHeader("Set-Cookie", "SESSID=synthetic-read-overlay; Path=/")
                    .addHeader("Set-Cookie", "uid=999; Path=/").addHeader("Set-Cookie", "token=unrelated-account; Path=/")
                else MockResponse().setBody(if (path.endsWith("/detail"))
                    """{"data":{"id":42,"entityType":"feed","message":"合成验证返回后的完整正文 VERIFIED_READ_BODY","replynum":0}}"""
                    else """{"data":[{"id":42,"entityType":"feed","message":"合成验证返回后的首页内容 VERIFIED_READ_BODY","replynum":0}]}""")
            }
        }
        server.start()
        client = OkHttpClient.Builder().dns(object : Dns { override fun lookup(host: String): List<InetAddress> {
            if (host !in setOf("localhost", "127.0.0.1", "::1")) { blockedHosts += host; throw java.net.UnknownHostException("External network forbidden in synthetic fixture") }
            return listOf(InetAddress.getByName(host))
        } }).build()
        store = AccountStore(object : CredentialPersistence {
            override fun read(): ByteArray? = null
            override fun write(bytes: ByteArray) = Unit
            override fun reset() = Unit
        }, guestDeviceId = "synthetic-ui-read-device")
        if (authenticated) {
            val origin = "https://api.coolapk.com/".toHttpUrl()
            val cookies = listOf("uid=123", "token=synthetic-owner", "SESSID=synthetic-owner-session").map { Cookie.parse(origin, "$it; Domain=coolapk.com; Path=/; Secure")!! }
            check(store.saveAuthenticated("123", "合成验证账号", "synthetic-ui-read-device", cookies, store.snapshot()))
        }
        // Unrelated global WebView account cookies must never be adopted by a guest read.
        CookieManager.getInstance().setCookie("https://account.coolapk.com/", "uid=999; Path=/; Secure")
        CookieManager.getInstance().setCookie("https://account.coolapk.com/", "token=unrelated-webview; Path=/; Secure")
        fun captured(): RequestSession {
            val source = store.captureRequestSession()
            return source.copy(cookieFor = { url -> source.cookieFor(url.newBuilder().scheme("https").host("api.coolapk.com").port(443).build()) })
        }
        val endpoints = ApiEndpoints(server.url("/"), server.url("/"), server.url("/"))
        val repository = HttpCoolapkRepository(client, endpoints, ClientProtocol("synthetic-ui-read-device", { 1770000000 }), requestSession = ::captured)
        val login = HttpLoginTransport(client)
        val social = SocialRepository(store) { CoolapkHttpTransport(client, endpoints, requestSession = ::captured) }
        val ctor = AccountRuntime::class.java.getDeclaredConstructor(AccountStore::class.java, HttpLoginTransport::class.java,
            LoginCoordinator::class.java, HttpCoolapkRepository::class.java, SocialRepository::class.java).apply { isAccessible = true }
        val accounts = ctor.newInstance(store, login, LoginCoordinator(store, login, "synthetic-ui-read-device"), repository, social)
        val container = AppContainer(compose.activity.application).also { it.nativeUpdates.enabled = false }
        AppContainer::class.java.getDeclaredField("local").apply { isAccessible = true }.set(container, LocalRepository(MemoryLocal()))
        model = BrowseViewModel(repository)
        compose.setContent {
            val state by store.state.collectAsState()
            CompositionLocalProvider(LocalReadingStyles provides readingStyles(ReadingPreset.COMFORTABLE, FontChoice.NOTO_SANS)) {
                MaterialTheme(colorScheme = fixedColorScheme(0, false), typography = appTypography(FontChoice.NOTO_SANS)) {
                    NativeApp(model, container, AppSettings(floatingNavigation = true, dynamicColor = false), accounts, state, initialRoute = if (detail) "feed:42" else null)
                }
            }
        }
        val key = if (detail) "feed:42" else "home"
        val prefix = if (detail) "detail" else "read"
        val name = (if (authenticated) "account" else "guest") + (if (detail) "-detail" else "-home")
        fun currentPending(): PendingRead<*> = model.states.value.getValue(key).failure!!.let { it.detailRead ?: requireNotNull(it.pageRead) }
        awaitTag("$prefix-human-verify"); val first = currentPending(); capture("$name-01-entry")
        compose.onNodeWithTag("$prefix-human-verify").performClick()
        compose.onNodeWithText("取消验证").assertIsDisplayed(); val firstHost = host()
        assertEquals(ChallengeOriginPolicy.PAGE, shadowOf(firstHost).lastLoadDataWithBaseURL.baseUrl)
        assertFalse(firstHost.settings.allowFileAccess); assertFalse(firstHost.settings.allowContentAccess)
        capture("$name-02-host", dialog = true)
        val oldBridge = bridge(firstHost)
        compose.onNodeWithText("取消验证").performClick()
        compose.onAllNodes(isDialog()).assertCountEquals(0)
        assertEquals(DetailVerificationState.CLOSED, first.state.value)
        assertTrue(shadowOf(firstHost).wasDestroyCalled()); assertNull(shadowOf(firstHost).getJavascriptInterface("CaptchaProof"))
        val countAfterCancel = apiCount(detail)
        oldBridge(); compose.waitForIdle(); assertEquals(countAfterCancel, apiCount(detail))
        capture("$name-03-cancelled")
        compose.onNodeWithText(if (detail) "重新加载动态" else "重试").performClick()
        awaitTag("$prefix-human-verify"); val second = currentPending(); assertNotSame(first, second)
        compose.onNodeWithTag("$prefix-human-verify").performClick(); val secondHost = host()
        oldBridge(); compose.waitForIdle(); assertEquals(DetailVerificationState.AWAITING_HUMAN, second.state.value)
        val beforeProof = apiCount(detail)
        bridge(secondHost)() // Synthetic SDK callback only; no automated challenge interaction.
        awaitTag("$prefix-verified-retry")
        compose.onAllNodes(isDialog()).assertCountEquals(0)
        assertTrue(shadowOf(secondHost).wasDestroyCalled()); assertEquals(beforeProof, apiCount(detail))
        capture("$name-04-return-explicit-retry")
        compose.onNodeWithTag("$prefix-verified-retry").performClick()
        compose.waitUntil(10_000) { compose.onAllNodes(hasText("VERIFIED_READ_BODY", substring = true)).fetchSemanticsNodes().isNotEmpty() }
        assertEquals(beforeProof + 1, apiCount(detail))
        if (detail) compose.waitUntil(10_000) {
            compose.onAllNodes(hasText("暂无评论", substring = true)).fetchSemanticsNodes().isNotEmpty()
        }
        capture("$name-05-read-success")
        val verified = requests.single { it.requestUrl!!.encodedPath == (if (detail) "/v6/feed/detail" else "/v6/main/indexV8") && it.getHeader("Cookie").orEmpty().contains("validate=") }
        assertEquals(if (detail) "/v6/feed/detail" else "/v6/main/indexV8", verified.requestUrl!!.encodedPath)
        val cookie = verified.getHeader("Cookie").orEmpty()
        assertFalse(cookie.contains("999")); assertFalse(cookie.contains("unrelated"))
        if (authenticated) { assertTrue(cookie.contains("uid=123")); assertTrue(cookie.contains("token=synthetic-owner")) }
        else { assertFalse(cookie.contains("uid=")); assertFalse(cookie.contains("token=")); assertNull(store.state.value.activeId) }
        assertTrue(CookieManager.getInstance().getCookie("https://account.coolapk.com/").contains("uid=999"))
        File("build/verification/read-verification/$name-provenance.txt").writeText(
            "Synthetic loopback API+share responses. Actual NativeApp, decoder, repository, ViewModel, HumanVerification and callback receiver.\n" +
                "ShadowWebView did not execute provider SDK. Callback is synthetic, not service acceptance.\n" +
                "Input=$challenge\nRoute=$key; syntheticAuthenticated=$authenticated; API reads=${apiCount(detail)}; external DNS=${blockedHosts.size}\n")
    }
    private fun apiCount(detail: Boolean) = requests.count { it.requestUrl!!.encodedPath == if (detail) "/v6/feed/detail" else "/v6/main/indexV8" }
    private fun awaitTag(tag: String) = compose.waitUntil(10_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
    private fun views(view: View): List<View> = listOf(view) + if (view is ViewGroup) (0 until view.childCount).flatMap { views(view.getChildAt(it)) } else emptyList()
    private fun host(): WebView {
        compose.onNodeWithText("取消验证").assertIsDisplayed()
        compose.waitForIdle()
        return compose.runOnUiThread {
            val shown = ShadowDialog.getShownDialogs().filter { it.isShowing }.distinct()
            check(shown.size == 1)
            views(requireNotNull(shown.single().window).decorView).filterIsInstance<WebView>().single()
        }
    }
    private fun bridge(view: WebView): () -> Unit {
        val shadow = shadowOf(view)
        assertEquals(ChallengeOriginPolicy.PAGE, view.url)
        assertEquals(ChallengeOriginPolicy.PAGE, shadow.lastLoadDataWithBaseURL.historyUrl)
        val receiver = requireNotNull(shadow.getJavascriptInterface("CaptchaProof"))
        val nonce = Regex("CaptchaProof\\.verified\\('([a-f0-9]+)'", RegexOption.MULTILINE).find(shadow.lastLoadDataWithBaseURL.data)!!.groupValues[1]
        val method = receiver.javaClass.getDeclaredMethod("verified", String::class.java, String::class.java).apply { isAccessible = true }
        return { compose.runOnUiThread { method.invoke(receiver, nonce, "synthetic-ui-proof") } }
    }
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
        File("build/verification/read-verification/$name.png").apply { parentFile!!.mkdirs() }.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
}
