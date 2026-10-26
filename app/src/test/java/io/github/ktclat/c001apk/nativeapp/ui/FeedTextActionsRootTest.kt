// SPDX-License-Identifier: AGPL-3.0-only
package io.github.ktclat.c001apk.nativeapp.ui

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.view.inspector.WindowInspector
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import io.github.ktclat.c001apk.nativeapp.AppContainer
import io.github.ktclat.c001apk.nativeapp.account.*
import io.github.ktclat.c001apk.nativeapp.data.*
import io.github.ktclat.c001apk.nativeapp.domain.*
import io.github.ktclat.c001apk.nativeapp.local.LocalLoadState
import io.github.ktclat.c001apk.nativeapp.local.loadStates
import io.github.ktclat.c001apk.nativeapp.platform.FeedTextTransfer
import io.github.ktclat.c001apk.nativeapp.settings.*
import io.github.ktclat.c001apk.nativeapp.social.SocialRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.io.IOException
import java.lang.reflect.Proxy
import java.util.concurrent.atomic.AtomicInteger

/** Actual NativeApp list/detail routes, synthetic read responses, no network or external send. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w393dp-h852dp-420dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FeedTextActionsRootTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val copies = mutableListOf<String>()
    private val shares = mutableListOf<String>()
    private val calls = mutableListOf<String>()
    private val transportCalls = AtomicInteger()
    private lateinit var client: OkHttpClient
    private lateinit var store: AccountStore
    private var failShare = false
    private var previousFontScale: Float? = null
    private val transfer = object : FeedTextTransfer {
        override fun copyLink(link: String) { copies += link }
        override fun shareText(text: String) {
            if (failShare) throw IllegalStateException("synthetic private detail must never reach feedback")
            shares += text
        }
    }

    @After fun cleanup() {
        compose.runOnUiThread { compose.activity.viewModelStore.clear(); previousFontScale?.let(RuntimeEnvironment::setFontScale) }
        if (::client.isInitialized) {
            client.dispatcher.cancelAll(); client.connectionPool.evictAll(); client.dispatcher.executorService.shutdownNow()
        }
        assertEquals("Copy/share must not cause an account or server mutation request", 0, transportCalls.get())
    }

    private fun render(route: String? = null, id: String = "123", publicRead: Boolean = false, realBoundary: Boolean = false) {
        store = AccountStore(object : CredentialPersistence {
            override fun read(): ByteArray? = null
            override fun write(bytes: ByteArray) = Unit
            override fun reset() = Unit
        })
        client = OkHttpClient.Builder().addInterceptor {
            transportCalls.incrementAndGet(); throw IOException("Synthetic fail-closed: networking disabled")
        }.build()
        val login = HttpLoginTransport(client)
        val constructor = AccountRuntime::class.java.getDeclaredConstructor(AccountStore::class.java,
            HttpLoginTransport::class.java, LoginCoordinator::class.java, HttpCoolapkRepository::class.java,
            SocialRepository::class.java).apply { isAccessible = true }
        val accounts = constructor.newInstance(store, login, LoginCoordinator(store, login, "synthetic"),
            HttpCoolapkRepository(client), SocialRepository(store) { CoolapkHttpTransport(client) })
        val item = ContentItem(id, "feed", message = "这是一条用于复制与分享验证的合成动态。<br>链接和正文摘要使用不同规则。",
            userName = "合成用户", userId = "2", feed = FeedMetadata(likeCount = 3, replyCount = 0), publicRead = publicRead)
        val repository = Proxy.newProxyInstance(CoolapkRepository::class.java.classLoader,
            arrayOf(CoolapkRepository::class.java)) { _, method, _ ->
            calls += method.name
            when (method.name) {
                "home" -> ApiResult.Success(Page(listOf(item), null))
                "feedDetail" -> ApiResult.Success(item)
                "orderedReplies" -> ApiResult.Success(Page(emptyList<ContentItem>(), null))
                else -> throw AssertionError("Unexpected synthetic read ${method.name}")
            }
        } as CoolapkRepository
        val model = BrowseViewModel(repository)
        val container = AppContainer(compose.activity.application).also { it.nativeUpdates.enabled = false }
        runBlocking { withTimeout(5_000) { container.local.loadStates().first { it is LocalLoadState.Ready } } }
        compose.setContent {
            val account by store.state.collectAsState()
            CompositionLocalProvider(LocalFeedTextTransfer provides if (realBoundary) null else transfer) {
                MaterialTheme(colorScheme = fixedColorScheme(0, false), typography = appTypography(FontChoice.NOTO_SANS)) {
                    NativeApp(model, container, AppSettings(dynamicColor = false, font = FontChoice.NOTO_SANS), accounts, account, initialRoute = route)
                }
            }
        }
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("feed-more:$id").fetchSemanticsNodes().isNotEmpty() }
        compose.waitUntil(10_000) { compose.onAllNodesWithText("这是一条用于复制与分享验证的合成动态。", substring = true).fetchSemanticsNodes().isNotEmpty() }
        compose.waitForIdle()
    }

    @Test fun ordinaryHomeAndDetailMenusAreReachableAndCopyIsLinkOnly() {
        render()
        assertTrue(copies.isEmpty()); assertTrue(shares.isEmpty())
        compose.onNodeWithTag("feed-more:123").performClick()
        compose.onNodeWithText("复制链接").assertIsDisplayed()
        compose.onNodeWithText("分享").assertIsDisplayed()
        compose.onNodeWithText("收藏到本机").assertIsDisplayed()
        compose.onNodeWithText("屏蔽").assertDoesNotExist(); compose.onNodeWithText("举报").assertDoesNotExist()
        capture("home-menu.png")
        compose.onNodeWithTag("feed-copy:123").performClick()
        compose.waitUntil(5_000) { copies.size == 1 }
        assertEquals(listOf("https://www.coolapk1s.com/feed/123"), copies)
        compose.onNodeWithText("已复制动态链接").assertIsDisplayed()
        compose.onNodeWithTag("feed-copy:123").assertDoesNotExist()
        capture("home-copy-feedback.png")
        compose.onNodeWithTag("feed-body:123").performClick()
        compose.onNodeWithTag("feed-detail-list").assertExists()
        compose.onNodeWithTag("feed-more:123").performClick()
        capture("detail-menu.png")
        compose.onNodeWithTag("feed-share:123").performClick()
        compose.waitUntil(5_000) { shares.size == 1 }
        assertEquals("这是一条用于复制与分享验证的合成动态。 链接和正文摘要使用不同规则。\nhttps://www.coolapk1s.com/feed/123\nhttps://www.coolapk.com/feed/123\n分享自c001apk", shares.single())
        compose.onNodeWithText("已打开系统分享面板，可选择应用或取消").assertIsDisplayed()
        assertTrue(calls.containsAll(listOf("home", "feedDetail", "orderedReplies")))
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
        assertEquals(1, shares.size); assertEquals(1, copies.size)
    }

    @Test fun publicGuestDetailCanCopyShareWithoutLoginAndErrorIsActionable() {
        render("feed:123", publicRead = true)
        failShare = true
        compose.onNodeWithTag("feed-more:123").performClick()
        compose.onNodeWithTag("feed-share:123").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("无法打开分享面板，请重试或复制链接").fetchSemanticsNodes().isNotEmpty() }
        capture("detail-share-error.png")
        compose.onNodeWithText("无法打开分享面板，请重试或复制链接").assertIsDisplayed()
        assertTrue(shares.isEmpty()); assertFalse(calls.contains("orderedReplies"))
        failShare = false
        compose.onNodeWithTag("feed-more:123").performClick()
        compose.onNodeWithTag("feed-copy:123").performClick()
        compose.waitUntil(5_000) { copies.size == 1 }
        assertNull(store.state.value.activeId)
        compose.onNodeWithTag("feed-like:123").assertIsEnabled().assert(
            SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.StateDescription, "登录后点赞"))
        assertEquals(1, copies.size); assertTrue(shares.isEmpty())
    }

    @Test fun nativeAppMenuClicksReachRealAndroidClipboardAndChooserWithoutTransferOverride() {
        // Null leaves the actual production AndroidFeedTextTransfer factory in use.
        render(realBoundary = true)
        val clipboard = compose.activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        assertNull(shadowOf(compose.activity).nextStartedActivity)
        compose.onNodeWithTag("feed-more:123").performClick()
        compose.onNodeWithTag("feed-copy:123").performClick()
        compose.waitUntil(5_000) { clipboard.primaryClip?.getItemAt(0)?.text?.toString() == "https://www.coolapk1s.com/feed/123" }
        val copied = clipboard.primaryClip!!
        assertEquals(1, copied.itemCount); assertEquals(1, copied.description.mimeTypeCount)
        assertEquals("text/plain", copied.description.getMimeType(0))
        assertNull(copied.getItemAt(0).uri); assertNull(copied.getItemAt(0).intent); assertNull(copied.getItemAt(0).htmlText)
        assertNull(shadowOf(compose.activity).nextStartedActivity)
        compose.onNodeWithTag("feed-more:123").performClick()
        compose.onNodeWithTag("feed-share:123").performClick()
        var launched: Intent? = null
        compose.waitUntil(5_000) {
            launched = launched ?: shadowOf(compose.activity).nextStartedActivity
            launched != null
        }
        val chooser = launched!!
        assertEquals(Intent.ACTION_CHOOSER, chooser.action)
        @Suppress("DEPRECATION") val send = chooser.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!
        val expected = "这是一条用于复制与分享验证的合成动态。 链接和正文摘要使用不同规则。\nhttps://www.coolapk1s.com/feed/123\nhttps://www.coolapk.com/feed/123\n分享自c001apk"
        assertEquals(Intent.ACTION_SEND, send.action); assertEquals("text/plain", send.type)
        assertEquals(setOf(Intent.EXTRA_TEXT), send.extras!!.keySet()); assertEquals(expected, send.getStringExtra(Intent.EXTRA_TEXT))
        assertEquals(0, send.flags); assertNull(send.data); assertNull(send.`package`); assertNull(send.component)
        val clip = send.clipData!!
        assertEquals(1, clip.itemCount); assertEquals(1, clip.description.mimeTypeCount)
        assertEquals("text/plain", clip.description.getMimeType(0)); assertEquals(expected, clip.getItemAt(0).text.toString())
        assertNull(clip.getItemAt(0).uri); assertNull(clip.getItemAt(0).intent); assertNull(clip.getItemAt(0).htmlText)
        assertEquals(0, chooser.flags); assertNull(chooser.data); assertNull(chooser.`package`); assertNull(chooser.component)
        assertNull(shadowOf(compose.activity).nextStartedActivity)
        assertTrue(copies.isEmpty()); assertTrue(shares.isEmpty()) // The recording override was never called.
    }

    @Test fun largeFontDetailMenuKeepsAllThreeActionsReachable() {
        compose.runOnUiThread {
            previousFontScale = RuntimeEnvironment.getFontScale()
            RuntimeEnvironment.setFontScale(2f)
        }
        render("feed:123")
        compose.onNodeWithTag("feed-more:123").performClick()
        compose.onNodeWithText("复制链接").assertIsDisplayed()
        compose.onNodeWithText("分享").assertIsDisplayed()
        compose.onNodeWithText("收藏到本机").assertIsDisplayed()
        capture("detail-menu-large-font.png")
        assertTrue(copies.isEmpty()); assertTrue(shares.isEmpty())
    }

    @Test fun malformedFeedIdHasNoCopyShareActionsButKeepsFavorite() {
        render("feed:bad?auth=synthetic", id = "bad?auth=synthetic")
        compose.onNodeWithTag("feed-more:bad?auth=synthetic").performClick()
        compose.onNodeWithText("复制链接").assertDoesNotExist()
        compose.onNodeWithText("分享").assertDoesNotExist()
        compose.onNodeWithText("收藏到本机").assertIsDisplayed()
        assertTrue(copies.isEmpty()); assertTrue(shares.isEmpty())
    }

    private fun capture(name: String) {
        compose.waitForIdle()
        val bitmap = compose.runOnUiThread {
            val content = compose.activity.findViewById<View>(android.R.id.content)
            val target = Bitmap.createBitmap(content.width, content.height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(target)
            // Draw the actual activity and popup window roots in window order, not a mock menu.
            WindowInspector.getGlobalWindowViews().filter { it.visibility == View.VISIBLE }.forEach { view ->
                val location = IntArray(2); view.getLocationOnScreen(location)
                canvas.save(); canvas.translate(location[0].toFloat(), location[1].toFloat()); view.draw(canvas); canvas.restore()
            }
            target
        }
        val file = File("build/verification/feed-copy-share/$name"); file.parentFile?.mkdirs()
        file.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        bitmap.recycle()
    }
}
