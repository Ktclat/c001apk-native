// SPDX-License-Identifier: AGPL-3.0-only
package io.github.ktclat.c001apk.nativeapp.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import io.github.ktclat.c001apk.nativeapp.AppContainer
import io.github.ktclat.c001apk.nativeapp.account.*
import io.github.ktclat.c001apk.nativeapp.data.*
import io.github.ktclat.c001apk.nativeapp.diagnostics.HomeRequestDiagnostics
import io.github.ktclat.c001apk.nativeapp.settings.AppSettings
import io.github.ktclat.c001apk.nativeapp.social.SocialRepository
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

/** Actual Home request -> My -> Settings -> Protocol -> Local diagnostics -> existing Copy, with only local clipboard writes. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w393dp-h852dp-420dpi")
class HomeDiagnosticsCopyRouteTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val clients = mutableListOf<OkHttpClient>()
    @After fun cleanup() { clients.forEach { it.dispatcher.cancelAll(); it.connectionPool.evictAll(); it.dispatcher.executorService.shutdownNow() } }
    @Test fun home403AppearsOnlyAfterExplicitCopyAndNavigationDoesNotRequestAgain() {
        val store = AccountStore(object : CredentialPersistence {
            override fun read(): ByteArray? = null
            override fun write(bytes: ByteArray) = Unit
            override fun reset() = Unit
        }, guestDeviceId = "SYNTHETIC_DEVICE_SECRET")
        val calls = AtomicInteger()
        val home = OkHttpClient.Builder().addInterceptor { chain ->
            calls.incrementAndGet()
            check(chain.request().url.encodedPath == "/v6/main/indexV8")
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(403).message("Synthetic")
                .header("Content-Type", "text/html").body("<html>SYNTHETIC_BODY_SECRET</html>".toResponseBody()).build()
        }.build()
        val blocked = OkHttpClient.Builder().addInterceptor { throw IOException("Synthetic: no login/social networking") }.build()
        clients.addAll(listOf(home, blocked))
        val journal = HomeRequestDiagnostics(store) { store.state.value.generation }
        val repository = HttpCoolapkRepository(home, protocol = ClientProtocol(deviceCode = "SYNTHETIC_DEVICE_SECRET"),
            requestSession = { store.captureRequestSession() }, homeDiagnostics = journal)
        val login = HttpLoginTransport(blocked)
        val social = SocialRepository(store) { CoolapkHttpTransport(blocked) }
        val ctor = AccountRuntime::class.java.getDeclaredConstructor(AccountStore::class.java, HttpLoginTransport::class.java,
            LoginCoordinator::class.java, HttpCoolapkRepository::class.java, SocialRepository::class.java).apply { isAccessible = true }
        val accounts = ctor.newInstance(store, login, LoginCoordinator(store, login, "SYNTHETIC_DEVICE_SECRET"), repository, social)
        val container = AppContainer(compose.activity.application).also { it.nativeUpdates.enabled = false }
        val clipboard = compose.activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("fixture", "unchanged before explicit Copy"))
        val model = BrowseViewModel(repository)
        compose.setContent {
            val accountState by store.state.collectAsState()
            MaterialTheme { NativeApp(model, container, AppSettings(dynamicColor = false), accounts, accountState) }
        }
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("动态").fetchSemanticsNodes().isNotEmpty() && journal.snapshot().size == 1
        }
        compose.onNode(hasText("我的") and hasClickAction()).performClick()
        compose.onNodeWithContentDescription("设置").performClick()
        compose.onNodeWithText("协议与设备").performScrollTo().performClick()
        compose.onNodeWithText("本地诊断").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("版本、界面与脱敏首页请求摘要；仅手动复制，不含账号凭据").assertIsDisplayed()
        compose.onNodeWithText("本地诊断").performClick()
        compose.onNodeWithTag("native-diagnostics").assertExists()
        assertEquals("unchanged before explicit Copy", clipboard.primaryClip!!.getItemAt(0).text.toString())
        assertEquals(1, calls.get())
        compose.onNodeWithTag("copy-native-diagnostics").performClick()
        val text = clipboard.primaryClip!!.getItemAt(0).text.toString()
        assertTrue(text.contains("Git commit")); assertTrue(text.contains("tab=DYNAMIC")); assertTrue(text.contains("HTTP=403"))
        assertTrue(text.contains("contentType=HTML")); assertTrue(text.contains("mode=GUEST"))
        assertTrue(text.contains("关注为范围外")); assertTrue(text.contains("无记录不代表成功"))
        assertFalse(text.contains("SYNTHETIC")); assertFalse(text.contains("https://")); assertEquals(1, calls.get())
    }
    @Test fun explicitCopyRechecksGenerationEvenWhenDisplayedSnapshotHasNotRecomposed() {
        val store = AccountStore(object : CredentialPersistence {
            override fun read(): ByteArray? = null
            override fun write(bytes: ByteArray) = Unit
            override fun reset() = Unit
        }, guestDeviceId = "SYNTHETIC_DEVICE_SECRET")
        val journal = HomeRequestDiagnostics(store) { store.state.value.generation }
        journal.append(store.state.value.generation, io.github.ktclat.c001apk.nativeapp.diagnostics.HomeRequestEvent(
            io.github.ktclat.c001apk.nativeapp.diagnostics.HomeRequestTab.DYNAMIC,
            io.github.ktclat.c001apk.nativeapp.diagnostics.HomeRequestEndpoint.INDEX_V8,
            io.github.ktclat.c001apk.nativeapp.diagnostics.HomeRequestHost.API2,
            io.github.ktclat.c001apk.nativeapp.diagnostics.HomeRequestMode.GUEST, false,
            io.github.ktclat.c001apk.nativeapp.diagnostics.HomeProtocolInfo(2604201, 35, false), 403,
            io.github.ktclat.c001apk.nativeapp.diagnostics.HomeContentType.HTML,
            io.github.ktclat.c001apk.nativeapp.domain.FailureKind.HTTP, 1))
        val clipboard = compose.activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        compose.setContent { MaterialTheme {
            io.github.ktclat.c001apk.nativeapp.diagnostics.NativeDiagnosticsScreen(false, SystemAccessibility(false, false), {}, journal)
        } }
        compose.onNodeWithTag("copy-native-diagnostics").performClick()
        assertTrue(clipboard.primaryClip!!.getItemAt(0).text.toString().contains("HTTP=403"))
        compose.runOnIdle { store.useGuest() }
        compose.onNodeWithTag("copy-native-diagnostics").performClick()
        val text = clipboard.primaryClip!!.getItemAt(0).text.toString()
        assertFalse(text.contains("HTTP=403")); assertTrue(text.contains("暂无当前账号代际"))
    }

}
