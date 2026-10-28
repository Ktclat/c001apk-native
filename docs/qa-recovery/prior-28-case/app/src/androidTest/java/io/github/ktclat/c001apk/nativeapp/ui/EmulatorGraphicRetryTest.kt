// SPDX-License-Identifier: AGPL-3.0-only
package io.github.ktclat.c001apk.nativeapp.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.ktclat.c001apk.nativeapp.AppContainer
import io.github.ktclat.c001apk.nativeapp.account.*
import io.github.ktclat.c001apk.nativeapp.data.*
import io.github.ktclat.c001apk.nativeapp.feature.media.LocalMediaImageLoader
import io.github.ktclat.c001apk.nativeapp.local.*
import io.github.ktclat.c001apk.nativeapp.settings.*
import io.github.ktclat.c001apk.nativeapp.social.SocialRepository
import kotlinx.coroutines.flow.MutableStateFlow
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.*
import org.junit.Assert.*
import org.junit.rules.TestName
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

/** UI-driven synthetic graphic verification, NOT a real CAPTCHA/provider/service claim. */
@RunWith(AndroidJUnit4::class)
class EmulatorGraphicRetryTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @get:Rule val testName = TestName()
    private val requests = CopyOnWriteArrayList<Request>()
    private val denied = CopyOnWriteArrayList<String>()
    private val responses = CopyOnWriteArrayList<String>()
    private val imageReads = java.util.concurrent.atomic.AtomicInteger()
    private val validationReads = java.util.concurrent.atomic.AtomicInteger()
    private val verified = AtomicBoolean(false)
    private lateinit var client: OkHttpClient
    private lateinit var images: EmulatorFixtureImages
    private lateinit var store: AccountStore
    private var target = "/v6/user/followList"
    private class MemoryLocal : JsonStateStore<LocalState> {
        override val data = MutableStateFlow(LocalState())
        override suspend fun update(transform: (LocalState) -> LocalState) { data.value = transform(data.value) }
    }
    @After fun close() {
        runCatching { EmulatorEvidence.capture("retry-${testName.methodName}-terminal") }
        if (::images.isInitialized) images.close()
        if (::client.isInitialized) { client.dispatcher.cancelAll(); client.connectionPool.evictAll(); client.dispatcher.executorService.shutdownNow() }
        EmulatorEvidence.text("retry-${testName.methodName}-requests.txt", requests.joinToString("\n") { "${it.method} ${it.url}" } + "\nResponses=${responses.joinToString("\n")}\nDenied=$denied\nValidation POSTs=${validationReads.get()} image GETs=${imageReads.get()}")
        assertTrue("Unexpected network paths denied before sockets: $denied", denied.isEmpty())
    }
    @Test fun initialGraphicGenericRetryConsumesProofOnceAndCannotReplayStaleClick() = exercise(more = false, counts = false)
    @Test fun failedMoreGraphicGenericRetryPreservesRowsAndExactCursor() = exercise(more = true, counts = false)
    @Test fun countsGraphicGenericRetryDoesNotReloadProfile() = exercise(more = false, counts = true)
    @Test fun cancelledGraphicThenExplicitRefreshCreatesFreshChallenge() {
        show(more = false, counts = false)
        awaitTag("personal-human-verify"); clickTag("personal-human-verify")
        awaitText("完成图形验证"); capture("graphic-before-cancel")
        compose.onNodeWithText("取消", substring = false).performClick()
        awaitText("验证已关闭或失效，可重新加载内容。")
        assertEquals(1, reads()); assertEquals(0, validationReads.get())
        clickText("刷新"); awaitTag("personal-human-verify"); assertEquals(2, reads())
        verify("personal"); assertEquals(2, reads())
        clickText("刷新"); awaitTag("personal-human-verify")
        assertEquals(3, reads()); compose.onNodeWithTag("personal-verified-retry").assertDoesNotExist()
        capture("explicit-refresh-discards-proof")
    }
    private fun exercise(more: Boolean, counts: Boolean) {
        show(more, counts)
        val prefix = if (counts) "counts" else "personal"
        val retry = if (counts) "重试未读数量" else "重试"
        if (more) { awaitText("旧联系人"); clickText("加载更多") }
        awaitTag("$prefix-human-verify"); capture("challenge")
        val oldClick = compose.onNodeWithText(retry).fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
        val before = reads()
        verify(prefix)
        assertEquals("Verification does not replay read", before, reads())
        capture("verified-awaiting-explicit-retry")
        // The visible generic retry is primary. The retained semantics wrapper only probes
        // duplicate UI-node replay, not immutable old Kotlin callback replacement ownership.
        clickText(retry)
        if (counts) awaitTag("my-notice-badge-MENTIONS") else awaitText("新联系人")
        assertEquals(before + 1, reads())
        compose.runOnIdle { oldClick(); oldClick() }
        compose.waitForIdle(); assertEquals("Stale pre-verification click cannot replay", before + 1, reads())
        assertEquals(1, validationReads.get())
        val accountCookie = store.captureRequestSession().cookieFor(requests.first { it.url.encodedPath == target }.url).orEmpty()
        assertFalse("Challenge cookies must never reach account persistence", accountCookie.contains("emulator-verified") || accountCookie.contains("emulator-challenge"))
        if (more) {
            compose.onNodeWithText("旧联系人").assertExists()
            val targetReads = requests.filter { it.url.encodedPath == target }
            assertEquals(listOf("1", "2", "2"), targetReads.map { it.url.queryParameter("page") })
            assertEquals(targetReads[1].url, targetReads[2].url)
            assertEquals("101", targetReads[2].url.queryParameter("firstItem"))
            assertEquals("101", targetReads[2].url.queryParameter("lastItem"))
        }
        if (counts) assertEquals(1, requests.count { it.url.encodedPath == "/v6/user/profile" })
        capture("generic-retry-success")
    }
    private fun verify(prefix: String) {
        clickTag("$prefix-human-verify")
        awaitText("完成图形验证")
        compose.waitUntil(20_000) { compose.onAllNodesWithContentDescription("待输入的图形验证码").fetchSemanticsNodes().isNotEmpty() }
        capture("visible-synthetic-graphic")
        compose.onNode(hasSetTextAction()).performTextInput("AB12")
        val device = androidx.test.uiautomator.UiDevice.getInstance(androidx.test.platform.app.InstrumentationRegistry.getInstrumentation())
        compose.waitUntil(20_000) { imeWindowVisible() }
        capture("visible-code-entered")
        graphicWindowReceipt("code-entered")
        // Normal user Back dismisses only the positively observed IME, not this challenge.
        device.pressBack()
        compose.waitUntil(20_000) { !imeWindowVisible() }
        compose.onNodeWithText("完成图形验证").assertExists()
        assertEquals("AB12", compose.onNode(hasSetTextAction()).fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.EditableText].text)
        compose.onNodeWithText("提交验证").performScrollTo().assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithText("取消", substring = false).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("提交验证").assertIsDisplayed().assertIsEnabled()
        assertEquals("AB12", compose.onNode(hasSetTextAction()).fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.EditableText].text)
        capture("visible-submit-reachable")
        graphicWindowReceipt("submit-reachable")
        compose.onNodeWithText("提交验证").assertIsDisplayed().performTouchInput { click() }
        awaitTag("$prefix-verified-retry")
    }
    private fun imeWindowVisible(): Boolean =
        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation.windows.any {
            it.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_INPUT_METHOD
        }
    private fun graphicWindowReceipt(stage: String) {
        val name = "retry-${testName.methodName}-$stage"
        EmulatorEvidence.windowDump(name)
        val host = compose.runOnUiThread {
            val decor = compose.activity.window.decorView
            val frame = android.graphics.Rect().also(decor::getWindowVisibleDisplayFrame)
            val insets = androidx.core.view.ViewCompat.getRootWindowInsets(decor)
            "hostSoftInputMode=0x${compose.activity.window.attributes.softInputMode.toString(16)} " +
                "hostVisibleFrame=$frame hostImeVisible=${insets?.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime())} " +
                "hostImeInsets=${insets?.getInsets(androidx.core.view.WindowInsetsCompat.Type.ime())}"
        }
        val submit = compose.onNodeWithText("提交验证").fetchSemanticsNode().boundsInWindow
        val cancel = compose.onNodeWithText("取消", substring = false).fetchSemanticsNode().boundsInWindow
        EmulatorEvidence.text("$name-geometry.txt", "$host\nActual accessibility IME window visible=${imeWindowVisible()}\nSubmit window bounds=$submit\nCancel window bounds=$cancel\nSee full focused dialog/IME window dump; host alone is not the active dialog proof.")
    }
    private fun show(more: Boolean, counts: Boolean) {
        EmulatorEvidence.configureWindow(compose.activity, false)
        target = if (counts) "/v6/notification/checkCount" else "/v6/user/followList"
        images = EmulatorFixtureImages(compose.activity)
        store = AccountStore(object : CredentialPersistence {
            override fun read(): ByteArray? = null
            override fun write(bytes: ByteArray) = Unit
            override fun reset() = Unit
        }, "synthetic-emulator-graphic")
        val cookie = Cookie.Builder().name("token").value("synthetic-emulator-only").domain("coolapk.com").path("/").secure().expiresAt(System.currentTimeMillis()+3_600_000).build()
        check(store.saveAuthenticated("123", "合成账号", "synthetic-emulator-graphic", listOf(cookie), store.snapshot()))
        val png = graphic()
        client = OkHttpClient.Builder().addInterceptor { chain ->
            val req = chain.request(); requests += req
            fun reject(): Nothing { denied += "${req.method} ${req.url}"; throw IOException("Fixture denied before sockets") }
            if (req.url.host !in setOf("api.coolapk.com", "api2.coolapk.com") || req.url.scheme != "https") reject()
            val path = req.url.encodedPath
            var contentType = "application/json"
            var sessionCookie: String? = null
            val bytes: ByteArray = if (path == "/v6/account/requestValidate") {
                if (req.method != "POST") reject()
                val form = req.body as? MultipartBody ?: reject()
                if (form.parts.size != 2) reject()
                val fields = form.parts.associate { part ->
                    val key = Regex("name=\"([^\"]+)\"").find(part.headers?.get("Content-Disposition").orEmpty())?.groupValues?.get(1) ?: reject()
                    val buffer = Buffer(); part.body.writeTo(buffer); key to buffer.readUtf8()
                }
                if (fields != mapOf("type" to "err_request_captcha", "code" to "AB12")) reject()
                validationReads.incrementAndGet(); verified.set(true)
                sessionCookie = "SESSID=emulator-verified; Path=/; Secure"
                """{"data":"验证通过"}""".toByteArray()
            } else {
                if (req.method != "GET") reject()
                when (path) {
                    "/v6/account/captchaImage" -> {
                        check(req.url.queryParameterNames == setOf("_", "w", "h"))
                        check(req.url.queryParameter("w") == "270" && req.url.queryParameter("h") == "113")
                        imageReads.incrementAndGet(); contentType = "image/png"; png
                    }
                    target -> {
                        val success = req.header("Cookie").orEmpty().contains("SESSID=emulator-verified") && verified.getAndSet(false)
                        val body = if (more && req.url.queryParameter("page") == "1") contacts("101", "旧联系人", false)
                        else if (success) if (counts) """{"data":{"atme":7,"atcommentme":0,"feedlike":0,"contacts_follow":0,"message":0,"notification":0}}""" else contacts("102", "新联系人", true)
                        else """{"messageStatus":"err_request_captcha"}"""
                        if (body.contains("err_request_captcha")) sessionCookie = "SESSID=emulator-challenge; Path=/; Secure"
                        body.toByteArray()
                    }
                    "/v6/user/profile" -> """{"data":{"uid":"123","username":"合成账号","feed":0,"follow":0,"fans":0}}""".toByteArray()
                    "/v6/main/indexV8" -> """{"data":[]}""".toByteArray()
                    else -> reject()
                }
            }
            responses += "${req.method} $path contentType=$contentType cookie=$sessionCookie body=${if (contentType == "image/png") "LOCAL_PNG" else bytes.toString(Charsets.UTF_8)}"
            Response.Builder().request(req).protocol(Protocol.HTTP_1_1).code(200).message("LOCAL SYNTHETIC FIXTURE").apply { sessionCookie?.let { addHeader("Set-Cookie", it) } }.body(bytes.toResponseBody(contentType.toMediaType())).build()
        }.build()
        val repository = HttpCoolapkRepository(client = client, requestSession = { store.captureRequestSession() })
        val login = HttpLoginTransport(client)
        val social = SocialRepository(store) { snapshot -> CoolapkHttpTransport(client = client, requestSession = { store.requestSessionFor(snapshot) }) }
        val ctor = AccountRuntime::class.java.getDeclaredConstructor(AccountStore::class.java, HttpLoginTransport::class.java, LoginCoordinator::class.java, HttpCoolapkRepository::class.java, SocialRepository::class.java).apply { isAccessible = true }
        val accounts = ctor.newInstance(store, login, LoginCoordinator(store, login, "synthetic-emulator-graphic"), repository, social)
        val container = AppContainer(compose.activity.application).also { it.nativeUpdates.enabled = false }
        AppContainer::class.java.getDeclaredField("local").apply { isAccessible = true }.set(container, LocalRepository(MemoryLocal()))
        val model = BrowseViewModel(repository)
        compose.setContent {
            val state by store.state.collectAsState()
            CompositionLocalProvider(LocalMediaImageLoader provides images.loader, LocalReadingStyles provides readingStyles(ReadingPreset.COMFORTABLE, FontChoice.NOTO_SANS)) {
                MaterialTheme(colorScheme = fixedColorScheme(0, false), typography = appTypography(FontChoice.NOTO_SANS)) {
                    NativeApp(model, container, AppSettings(floatingNavigation = false, dynamicColor = false), accounts, state, initialRoute = if (counts) null else "mylist:FOLLOW")
                }
            }
        }
        if (counts) { awaitTag("standard-navigation"); compose.onNodeWithText("我的").assertIsDisplayed().performClick() }
    }
    private fun contacts(id: String, name: String, terminal: Boolean): String = """{"data":[{"id":"$id","entityType":"contacts","fUserInfo":{"uid":"$id","username":"$name","userAvatar":"https://fixture.invalid/icon-5.png","follow":120,"fans":980}}${if (terminal) ",{\"id\":\"999\",\"entityType\":\"card\",\"entityTemplate\":\"noMoreDataCard\"}" else ""}]}"""
    private fun graphic(): ByteArray {
        val bitmap = Bitmap.createBitmap(270,113,Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap); canvas.drawColor(Color.WHITE)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = 52f; typeface = android.graphics.Typeface.MONOSPACE }
        canvas.drawText("AB12", 55f, 75f, paint)
        return ByteArrayOutputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it); bitmap.recycle(); it.toByteArray() }
    }
    private fun reads() = requests.count { it.url.encodedPath == target }
    private fun awaitTag(tag: String) = compose.waitUntil(30_000) { compose.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
    private fun awaitText(text: String) = compose.waitUntil(30_000) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
    private fun clickTag(tag: String) { compose.onNodeWithTag(tag).performScrollTo().performClick() }
    private fun clickText(text: String) { compose.onNodeWithText(text, substring = false).performScrollTo().performClick() }
    private fun capture(name: String) { compose.waitForIdle(); EmulatorEvidence.capture("retry-${testName.methodName}-$name", compose.onAllNodes(isRoot(),useUnmergedTree=true).printToString()) }
}
