// SPDX-License-Identifier: AGPL-3.0-only
// New test-only fixture. All API calls stop in this strict interceptor before sockets.
package io.github.ktclat.c001apk.nativeapp.ui

import android.content.ContextWrapper
import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.unit.Density
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.test.ext.junit.rules.ActivityScenarioRule
import io.github.ktclat.c001apk.nativeapp.AppContainer
import io.github.ktclat.c001apk.nativeapp.account.*
import io.github.ktclat.c001apk.nativeapp.data.*
import io.github.ktclat.c001apk.nativeapp.local.*
import io.github.ktclat.c001apk.nativeapp.settings.*
import io.github.ktclat.c001apk.nativeapp.social.SocialRepository
import kotlinx.coroutines.flow.MutableStateFlow
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList

class EmulatorReadOnlyFixture(
    val compose: AndroidComposeTestRule<ActivityScenarioRule<ComponentActivity>, ComponentActivity>,
    private val label: String,
    private val responder: (Request) -> String?,
) : AutoCloseable {
    class Memory<T>(initial: T) : JsonStateStore<T> {
        override val data = MutableStateFlow(initial)
        override suspend fun update(transform: (T) -> T) { data.value = transform(data.value) }
    }
    val requests = CopyOnWriteArrayList<Request>()
    val denied = CopyOnWriteArrayList<String>()
    val browserIntents = CopyOnWriteArrayList<String>()
    val settings = Memory(AppSettings(dynamicColor = false, floatingNavigation = false))
    val store = AccountStore(object : CredentialPersistence {
        override fun read(): ByteArray? = null
        override fun write(bytes: ByteArray) = Unit
        override fun reset() = Unit
    }, "synthetic-qa-device")
    private val client = OkHttpClient.Builder().addInterceptor { chain ->
        val request = chain.request()
        if (request.method != "GET" || request.url.scheme != "https" || request.url.port != 443 ||
            request.url.host !in setOf("api.coolapk.com", "api2.coolapk.com")) {
            denied += "${request.method} ${request.url.scheme}://${request.url.host}:${request.url.port}${request.url.encodedPath}"
            throw IOException("Synthetic read-only fixture denied request before sockets")
        }
        val body = responder(request) ?: run {
            denied += "${request.method} ${request.url.encodedPath}"
            throw IOException("Synthetic route not allowlisted")
        }
        requests += request
        Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("synthetic")
            .body(body.toResponseBody()).build() // Never chain.proceed.
    }.build()
    private val login = HttpLoginTransport(client)
    val repository = HttpCoolapkRepository(client = client, requestSession = { store.captureRequestSession() })
    private val social = SocialRepository(store) { snapshot -> CoolapkHttpTransport(client = client,
        requestSession = { store.requestSessionFor(snapshot) }) }
    val accounts: AccountRuntime = AccountRuntime::class.java.getDeclaredConstructor(AccountStore::class.java,
        HttpLoginTransport::class.java, LoginCoordinator::class.java, HttpCoolapkRepository::class.java,
        SocialRepository::class.java).apply { isAccessible = true }.newInstance(store, login,
        LoginCoordinator(store, login, "synthetic-qa-device"), repository, social)
    val container = AppContainer(compose.activity.application).also { it.nativeUpdates.enabled = false }
    val model = BrowseViewModel(repository)
    private val owner = SessionViewModelStore()

    fun render(initialRoute: String? = null, dark: Boolean = false, scale: Float = 1f, authenticated: Boolean = true, localState: LocalState = LocalState()) {
        EmulatorEvidence.configureWindow(compose.activity, dark)
        if (authenticated) {
            val cookie = Cookie.Builder().name("token").value("synthetic-qa-only").domain("coolapk.com").path("/")
                .secure().expiresAt(System.currentTimeMillis() + 3_600_000).build()
            check(store.saveAuthenticated("1", "合成测试账号", "synthetic-qa-device", listOf(cookie), store.snapshot()))
        }
        AppContainer::class.java.getDeclaredField("local").apply { isAccessible = true }.set(container, LocalRepository(Memory(localState)))
        AppContainer::class.java.getDeclaredField("settings").apply { isAccessible = true }.set(container, SettingsRepository(settings))
        owner.select(store.state.value.generation)
        owner.viewModelStore.put("qa-readonly", model)
        val context = object : ContextWrapper(compose.activity) {
            override fun startActivity(intent: Intent) { browserIntents += intent.toUri(0); error("External navigation not permitted in synthetic fixture") }
        }
        compose.setContent {
            val account by store.state.collectAsState()
            val preferences by settings.data.collectAsState()
            MaterialTheme(colorScheme = fixedColorScheme(0, dark), typography = appTypography(FontChoice.NOTO_SANS)) {
                CompositionLocalProvider(LocalContext provides context, LocalViewModelStoreOwner provides owner,
                    LocalDensity provides Density(LocalDensity.current.density, scale),
                    LocalReadingStyles provides readingStyles(ReadingPreset.COMFORTABLE, FontChoice.NOTO_SANS),
                    LocalShowEmoticons provides preferences.showEmoji) {
                    NativeApp(model, container, preferences, accounts, account, initialRoute = initialRoute)
                }
            }
        }
    }
    fun awaitTag(tag: String) = compose.waitUntil(30_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().size == 1 }
    fun awaitText(text: String) = compose.waitUntil(30_000) { compose.onAllNodesWithText(text).fetchSemanticsNodes().size == 1 }
    fun awaitReplies(id: String) = compose.waitUntil(30_000) {
        compose.onRoot().fetchSemanticsNode()
        model.states.value["ordered:$id:RECENT_REPLY:false"]?.let { it.loaded && !it.loading && it.error == null } == true
    }
    fun back() { compose.onNodeWithContentDescription("返回").performClick() }
    fun count(path: String, id: String? = null) = requests.count { it.url.encodedPath == path && (id == null || it.url.queryParameter("id") == id) }
    fun capture(stage: String) {
        compose.waitForIdle()
        EmulatorEvidence.capture("$label-$stage", compose.onAllNodes(isRoot(), useUnmergedTree = true).printToString())
        ledger()
    }
    fun ledger() = EmulatorEvidence.text("$label-requests.txt", requests.joinToString("\n") { "${it.method} ${it.url}" } +
        "\nDenied=$denied\nBrowserIntents=$browserIntents\nScope=synthetic GET-only fixture, no live account or CDN proof\n")
    override fun close() {
        val errors = mutableListOf<Throwable>()
        fun attempt(block: () -> Unit) { try { block() } catch (error: Throwable) { errors += error } }
        attempt { ledger() }
        attempt { owner.viewModelStore.clear() }
        attempt { client.dispatcher.cancelAll() }
        attempt { client.connectionPool.evictAll() }
        attempt { client.dispatcher.executorService.shutdownNow() }
        attempt { assertTrue("No denied request: $denied", denied.isEmpty()) }
        attempt { assertTrue("No browser navigation: $browserIntents", browserIntents.isEmpty()) }
        attempt { assertTrue(requests.all { it.method == "GET" }) }
        val primary = EmulatorPrimaryFailure.current
        if (primary != null) errors.forEach(primary::addSuppressed)
        else if (errors.isNotEmpty()) { errors.drop(1).forEach(errors.first()::addSuppressed); throw errors.first() }
    }
}
