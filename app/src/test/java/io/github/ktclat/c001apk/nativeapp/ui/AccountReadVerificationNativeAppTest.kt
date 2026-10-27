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
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import io.github.ktclat.c001apk.nativeapp.AppContainer
import io.github.ktclat.c001apk.nativeapp.account.*
import io.github.ktclat.c001apk.nativeapp.data.*
import io.github.ktclat.c001apk.nativeapp.domain.*
import io.github.ktclat.c001apk.nativeapp.feature.media.LocalMediaImageLoader
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

/** Actual post-fix production roots, synthetic account/payload,
 * loopback API/share only and hard denial of external DNS. Not a service verification. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w393dp-h852dp-420dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AccountReadVerificationNativeAppTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private lateinit var server: MockWebServer
    private lateinit var client: OkHttpClient
    private lateinit var images: IconFixtureImages
    private val requests = CopyOnWriteArrayList<RecordedRequest>()
    private val blockedHosts = CopyOnWriteArrayList<String>()
    private var captureRoot = "build/verification/account-read-after"
    private val challenge = """{"messageStatus":"err_request_captcha_v2","messageExtra":{"captchaId":"abcdefghijklmnop","captchaField":""}}"""
    private class MemoryLocal : JsonStateStore<LocalState> {
        override val data = MutableStateFlow(LocalState())
        override suspend fun update(transform: (LocalState) -> LocalState) { data.value = transform(data.value) }
    }
    @After fun close() {
        if (::images.isInitialized) images.close()
        if (::client.isInitialized) { client.dispatcher.cancelAll(); client.connectionPool.evictAll(); client.dispatcher.executorService.shutdownNow() }
        if (::server.isInitialized) server.shutdown()
        assertTrue("No non-loopback DNS targets", blockedHosts.isEmpty())
    }

    @Test fun actualMyFollowEntryCancelReturnAndExplicitRetry() = exercise(true)
    @Test fun actualMyCountEntryCancelReturnAndExplicitRetry() = exercise(false)
    @Test fun actualMyFollowGenericRetryConsumesProofOnce() = exercise(true, genericRetry = true)
    @Test fun actualMyCountsGenericRetryConsumesProofOnce() = exercise(false, genericRetry = true)
    @Test fun actualMyFollowGenericRetryRejectsStaleCallbacks() = exercise(true, genericRetry = true, staleCallbacks = true)
    @Test fun actualMyCountsGenericRetryRejectsStaleCallbacks() = exercise(false, genericRetry = true, staleCallbacks = true)
    private fun exercise(personal: Boolean, genericRetry: Boolean = false, staleCallbacks: Boolean = false) {
        if (genericRetry) captureRoot = "build/verification/account-read-generic-${if (staleCallbacks) "stale" else "proof"}-after"
        images = IconFixtureImages(compose.activity)
        val successBody = if (personal) requireNotNull(javaClass.getResource("/reachable-cards/personal-follow.json")).readText()
            else """{"data":{"atme":7,"atcommentme":0,"feedlike":0,"contacts_follow":0,"message":0,"notification":0}}"""
        server = MockWebServer()
        server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests += request
                check(request.method == "GET")
                return when (request.requestUrl!!.encodedPath) {
                    "/v6/user/followList", "/v6/notification/checkCount" -> {
                        val verified = request.getHeader("Cookie").orEmpty().contains("validate=NEC%3Aabcdefgh%3Asynthetic-account-ui-proof")
                        if (!verified) MockResponse().setBody(challenge).addHeader("Set-Cookie", "SESSID=synthetic-account-read; Path=/")
                            .addHeader("Set-Cookie", "uid=999; Path=/").addHeader("Set-Cookie", "token=foreign; Path=/")
                        else MockResponse().setBody(successBody)
                    }
                    "/v6/user/profile" -> MockResponse().setBody("""{"data":{"uid":"123","username":"合成账号","feed":0,"follow":0,"fans":0}}""")
                    "/v6/main/indexV8" -> MockResponse().setBody("{\"data\":[]}")
                    else -> error("Unexpected synthetic request path")
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
            override fun write(bytes: ByteArray) = Unit
            override fun reset() = Unit
        }, "synthetic-account-read-device")
        val origin = "https://api.coolapk.com/".toHttpUrl()
        val cookies = listOf("uid=123", "token=synthetic-owner", "SESSID=synthetic-owner-session").map { Cookie.parse(origin, "$it; Domain=coolapk.com; Path=/; Secure")!! }
        check(store.saveAuthenticated("123", "合成账号", "synthetic-account-read-device", cookies, store.snapshot()))
        val mappedCallbacks = java.util.concurrent.atomic.AtomicInteger()
        fun official(url: HttpUrl): HttpUrl {
            check(url.scheme == "http" && url.host == server.hostName && url.port == server.port)
            return url.newBuilder().scheme("https").host("api.coolapk.com").port(443).build()
        }
        fun mapped(source: RequestSession): RequestSession = source.copy(
            cookieFor = { url -> source.cookieFor(official(url)) },
            acceptCookies = { url, headers -> source.acceptCookies(official(url), headers) },
            acceptChallengeCookies = { url, headers ->
                mappedCallbacks.incrementAndGet()
                source.acceptChallengeCookies?.invoke(official(url), headers)?.let(::mapped)
            },
        )
        fun captured(): RequestSession = mapped(store.captureRequestSession())
        val endpoints = ApiEndpoints(server.url("/"), server.url("/"), server.url("/"))
        val repository = HttpCoolapkRepository(client, endpoints, ClientProtocol("synthetic-account-read-device", { 1770000000 }), requestSession = ::captured)
        val login = HttpLoginTransport(client)
        val social = SocialRepository(store) { CoolapkHttpTransport(client, endpoints, requestSession = ::captured) }
        val ctor = AccountRuntime::class.java.getDeclaredConstructor(AccountStore::class.java, HttpLoginTransport::class.java,
            LoginCoordinator::class.java, HttpCoolapkRepository::class.java, SocialRepository::class.java).apply { isAccessible = true }
        val accounts = ctor.newInstance(store, login, LoginCoordinator(store, login, "synthetic-account-read-device"), repository, social)
        val container = AppContainer(compose.activity.application).also { it.nativeUpdates.enabled = false }
        AppContainer::class.java.getDeclaredField("local").apply { isAccessible = true }.set(container, LocalRepository(MemoryLocal()))
        val model = BrowseViewModel(repository)
        compose.setContent {
            val state by store.state.collectAsState()
            CompositionLocalProvider(LocalMediaImageLoader provides images.loader, LocalReadingStyles provides readingStyles(ReadingPreset.COMFORTABLE, FontChoice.NOTO_SANS)) {
                MaterialTheme(colorScheme = fixedColorScheme(0, false), typography = appTypography(FontChoice.NOTO_SANS)) {
                    NativeApp(model, container, AppSettings(floatingNavigation = true, dynamicColor = false), accounts, state,
                        initialRoute = if (personal) "mylist:FOLLOW" else null)
                }
            }
        }
        if (!personal) compose.onNodeWithText("我的").performClick()
        val error = "服务端要求验证码，当前请求未完成"
        val prefix = if (personal) "personal" else "counts"
        val name = if (personal) "my-follow" else "my-counts"
        val target = if (personal) "/v6/user/followList" else "/v6/notification/checkCount"
        fun readCount() = requests.count { it.requestUrl!!.encodedPath == target }
        fun awaitTag(tag: String) = compose.waitUntil(10_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
        awaitTag("$prefix-human-verify")
        compose.onNodeWithTag("$prefix-human-verify").performScrollTo().assertIsDisplayed()
        capture("$name-01-entry")
        compose.onNodeWithTag("$prefix-human-verify").performClick()
        val first = host(); val oldBridge = bridge(first)
        capture("$name-02-host", dialog = true)
        compose.onNodeWithText("取消验证").performClick()
        compose.onAllNodes(isDialog()).assertCountEquals(0)
        assertTrue(shadowOf(first).wasDestroyCalled()); assertNull(shadowOf(first).getJavascriptInterface("CaptchaProof"))
        val afterCancel = readCount(); oldBridge(); compose.waitForIdle(); assertEquals(afterCancel, readCount())
        compose.onNodeWithText("验证已关闭或失效，可重新加载内容。").performScrollTo().assertIsDisplayed()
        capture("$name-03-cancelled")
        val retryLabel = if (personal) "重试" else "重试未读数量"
        fun genericRetryClick() = compose.onNodeWithText(retryLabel).fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
        val oldSemantics = if (staleCallbacks) genericRetryClick() else null
        // Counts keeps the same TextButton mounted through loading. Compose's semantics
        // lambda deliberately reads the node's CURRENT onClick; it is not a frozen callback.
        // Snapshot the real old callback only for that case, and test the live current action
        // independently below. Personal's error item is disposed, so its semantics stays old.
        val oldCountsCallback = if (staleCallbacks && !personal) buttonCallback(oldSemantics!!) else null
        val staleRetry = if (oldCountsCallback != null) ({ oldCountsCallback(); true }) else oldSemantics
        // The currently displayed CLOSED capability still permits an ordinary fresh retry.
        compose.onNodeWithText(retryLabel).performScrollTo().performClick()
        awaitTag("$prefix-human-verify")
        val currentRetry = if (genericRetry) genericRetryClick() else null
        if (staleCallbacks) {
            if (!personal) {
                // Prove why retaining SemanticsActions.OnClick is different here: both
                // wrappers address B's live callback, while the frozen A callback is distinct.
                assertSame(buttonCallback(oldSemantics!!), buttonCallback(currentRetry!!))
            }
            compose.runOnIdle { staleRetry!!() }; compose.waitForIdle()
            assertEquals("Old failure callback cannot start a read for the replacement failure", 2, readCount())
            if (!personal) assertNotSame(oldCountsCallback, buttonCallback(currentRetry!!))
            compose.onNodeWithTag("$prefix-human-verify").assertExists()
        }
        compose.onNodeWithTag("$prefix-human-verify").performScrollTo().performClick()
        val second = host(); oldBridge(); compose.waitForIdle()
        compose.onAllNodesWithTag("$prefix-verified-retry").assertCountEquals(0)
        val beforeProof = readCount(); bridge(second)()
        awaitTag("$prefix-verified-retry")
        assertTrue(shadowOf(second).wasDestroyCalled()); assertEquals(beforeProof, readCount())
        compose.onNodeWithTag("$prefix-verified-retry").performScrollTo().assertIsDisplayed()
        capture("$name-04-return-explicit-retry")
        if (staleCallbacks) {
            compose.runOnIdle { staleRetry!!() }; compose.waitForIdle()
            assertEquals("Old callback cannot discard replacement proof", beforeProof, readCount())
            compose.onNodeWithTag("$prefix-verified-retry").assertExists()
        }
        if (genericRetry) {
            // Real button callback captured before proof observes the same now-VERIFIED failure.
            compose.runOnIdle { currentRetry!!(); currentRetry() }; compose.waitForIdle()
        } else compose.onNodeWithTag("$prefix-verified-retry").performClick()
        try { compose.waitUntil(10_000) {
            if (personal) compose.onAllNodesWithText("开源观察员").fetchSemanticsNodes().isNotEmpty()
            else compose.onAllNodesWithTag("my-notice-badge-MENTIONS", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }
        } catch (problem: Throwable) {
            capture("$name-failed-success-wait")
            println("ACCOUNT_ROOT_DIAGNOSTIC reads=${readCount()} total=${requests.size} verified=${requests.count { it.getHeader("Cookie").orEmpty().contains("validate=") }}")
            println(compose.onRoot(useUnmergedTree = true).printToString())
            throw problem
        }
        if (personal) {
            compose.onNodeWithText("开源观察员").performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("120关注").assertIsDisplayed()
            compose.onNodeWithText("980粉丝").assertIsDisplayed()
            compose.onNodeWithTag("entity-card:contacts:1002", useUnmergedTree = true).assertIsDisplayed()
            compose.waitForIdle()
            compose.waitUntil(5_000) { "https://fixture.invalid/icon-5.png" in images.loaded }
            compose.onNodeWithTag("entity-icon:contacts:1002", useUnmergedTree = true).assertIsDisplayed()
        } else {
            compose.onNodeWithTag("my-notice-MENTIONS").performScrollTo().assertIsDisplayed()
            compose.onAllNodesWithText("部分未读数量暂不可用").assertCountEquals(0)
        }
        compose.onAllNodesWithText(error).assertCountEquals(0)
        if (genericRetry) {
            compose.runOnIdle { currentRetry!!(); staleRetry?.invoke() }; compose.waitForIdle()
        }
        assertEquals(beforeProof + 1, readCount()); assertEquals(3, readCount())
        if (!personal) assertEquals(1, requests.count { it.requestUrl!!.encodedPath == "/v6/user/profile" })
        capture("$name-05-read-success", verifyAvatar = personal)
        val verified = requests.single { it.requestUrl!!.encodedPath == target && it.getHeader("Cookie").orEmpty().contains("validate=") }
        assertTrue(verified.getHeader("Cookie")!!.contains("uid=123")); assertFalse(verified.getHeader("Cookie")!!.contains("999")); assertFalse(verified.getHeader("Cookie")!!.contains("foreign"))
        if (personal) {
            assertEquals("123", verified.requestUrl!!.queryParameter("uid"))
            assertEquals("1", verified.requestUrl!!.queryParameter("page"))
            assertEquals(setOf("uid", "page"), verified.requestUrl!!.queryParameterNames)
            assertEquals(0L, verified.bodySize)
        }
        else { assertEquals(target, verified.path); assertEquals(0L, verified.bodySize) }
        val output = File(captureRoot).apply { mkdirs() }
        File(output, "$name-success-input.json").writeText(successBody)
        File(output, "$name-provenance.txt").writeText("Actual NativeApp/HTTP/repository/UI; synthetic authenticated identity/device and loopback responses.\n" +
            "ShadowWebView did not run provider SDK; callback synthetic, not CAPTCHA/service completion.\n" +
            "Test adapter maps only asserted loopback callback origins to captured official identity; externalDNS=${blockedHosts.size}; mappedCallbacks=${mappedCallbacks.get()}\n" +
            "GenericRetry=$genericRetry; staleCallbacks=$staleCallbacks\nPayload=$challenge\nRoute=${if (personal) "mylist:FOLLOW" else "root:My"}; target=$target; reads=${readCount()}\n")
    }
    /** Test-only snapshot of the actual callback behind the pinned Compose clickable node.
     * No app observer seam is installed. Fail loudly if this library implementation changes;
     * never silently treat its mutable semantics wrapper as a historical Kotlin callback. */
    @Suppress("UNCHECKED_CAST")
    private fun buttonCallback(semantics: () -> Boolean): () -> Unit {
        assertEquals("androidx.compose.foundation.AbstractClickableNode\$applySemantics\$1", semantics.javaClass.name)
        val owner = semantics.javaClass.getDeclaredField("this\$0").apply { isAccessible = true }
        assertEquals("androidx.compose.foundation.AbstractClickableNode", owner.type.name)
        val node = owner.get(semantics)
        val callback = owner.type.getDeclaredField("onClick").apply { isAccessible = true }.get(node) as () -> Unit
        println("COUNTS_CALLBACK_SNAPSHOT wrapper=${System.identityHashCode(semantics)} node=${System.identityHashCode(node)} callback=${System.identityHashCode(callback)}")
        return callback
    }
    private fun capture(name: String, dialog: Boolean = false, verifyAvatar: Boolean = false) {
        compose.waitForIdle()
        val bitmap = compose.runOnUiThread {
            val view = if (dialog) {
                val shown = ShadowDialog.getShownDialogs().filter { it.isShowing }.distinct(); check(shown.size == 1)
                requireNotNull(shown.single().window).decorView
            } else compose.activity.findViewById<View>(android.R.id.content)
            check(view.width > 0 && view.height > 0)
            Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888).also { view.draw(Canvas(it)) }
        }
        if (verifyAvatar) {
            val rect = compose.onNodeWithTag("entity-icon:contacts:1002", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            check(rect.left >= 0 && rect.top >= 0 && rect.right <= bitmap.width && rect.bottom <= bitmap.height)
            val original = requireNotNull(javaClass.getResourceAsStream("/icon-grid/icon-5.png")).use { requireNotNull(android.graphics.BitmapFactory.decodeStream(it)) }
            val colors = mutableMapOf<Int, Int>()
            for (y in 0 until original.height) for (x in 0 until original.width) {
                val pixel = original.getPixel(x, y)
                if (android.graphics.Color.alpha(pixel) > 200) colors[pixel] = (colors[pixel] ?: 0) + 1
            }
            val color = colors.maxBy { it.value }.key; original.recycle()
            var ink = 0
            for (y in rect.top.toInt() until rect.bottom.toInt()) for (x in rect.left.toInt() until rect.right.toInt()) if (bitmap.getPixel(x, y) == color) ink++
            assertTrue("Actual source-colored contact avatar rendered", ink > rect.width * rect.height / 10)
        }
        File("$captureRoot/$name.png").apply { parentFile!!.mkdirs() }.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
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
        return { compose.runOnUiThread { method.invoke(receiver, nonce, "synthetic-account-ui-proof") } }
    }
}
