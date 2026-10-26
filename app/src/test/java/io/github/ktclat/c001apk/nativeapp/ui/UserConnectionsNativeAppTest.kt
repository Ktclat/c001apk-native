// SPDX-License-Identifier: AGPL-3.0-only
package io.github.ktclat.c001apk.nativeapp.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.TextLayoutResult
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
import io.github.ktclat.c001apk.nativeapp.feature.feed.SyntheticFeedImageLoader
import io.github.ktclat.c001apk.nativeapp.feature.media.LocalMediaImageLoader
import io.github.ktclat.c001apk.nativeapp.local.*
import io.github.ktclat.c001apk.nativeapp.settings.*
import io.github.ktclat.c001apk.nativeapp.social.SocialRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flow
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList

/** Mounted production NativeApp, real HTTP decoder/model, synthetic responses and anonymous artwork.
 * Interceptor terminates every request: this is never live-account or physical-device evidence. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w393dp-h852dp-420dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class UserConnectionsNativeAppTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val requests = CopyOnWriteArrayList<Request>()
    private val unexpected = CopyOnWriteArrayList<String>()
    private val owner = SessionViewModelStore()
    private lateinit var images: SyntheticFeedImageLoader
    private lateinit var client: OkHttpClient
    private lateinit var store: AccountStore
    private lateinit var model: BrowseViewModel
    private val memory = MemoryLocal()
    private var previousSystemFontScale: Float? = null
    private var firstFailure = false
    private var moreFailure = false
    private var empty = false
    private var missing = false
    private var invalidContact = false
    private var challenge = false
    private var many = false
    private var capRows = false
    private var repeatedPages = false
    private var localFailure = false
    private var localPending = false
    private val localGate = MutableStateFlow<LocalState?>(null)
    private class MemoryLocal : JsonStateStore<LocalState> {
        override val data = MutableStateFlow(LocalState())
        override suspend fun update(transform: (LocalState) -> LocalState) { data.value = transform(data.value) }
    }
    @After fun close() {
        owner.viewModelStore.clear()
        previousSystemFontScale?.let { scale -> compose.runOnUiThread { org.robolectric.RuntimeEnvironment.setFontScale(scale) } }
        if (::client.isInitialized) { client.dispatcher.cancelAll(); client.connectionPool.evictAll(); client.dispatcher.executorService.shutdownNow() }
        if (::images.isInitialized) { images.close(); assertTrue(images.unexpectedUrls.isEmpty()) }
        assertTrue("No unexpected read/write: $unexpected", unexpected.isEmpty())
    }
    @Test fun headerLinksMountBothRealListsPreserveDuplicatesAndRawCursors() {
        show("user:7"); awaitTag("user-name:7"); awaitImages(); capture("after-profile-links")
        compose.onNodeWithTag("user-following:7").performClick(); awaitTag("user-connection:0")
        compose.onNodeWithText("TA关注的人").assertIsDisplayed()
        compose.onAllNodesWithTag("entity-card:contacts:record-first").assertCountEquals(2)
        capture("after-following")
        compose.onNodeWithTag("user-connections-list").performScrollToNode(hasTestTag("user-connections-more"))
        compose.onNodeWithTag("user-connections-more").performClick(); awaitTag("user-connection:3")
        compose.onNodeWithTag("user-connections-more").assertDoesNotExist()
        val more = requests.last { it.url.encodedPath == "/v6/user/followList" }
        assertEquals("7", more.url.queryParameter("uid")); assertEquals("2", more.url.queryParameter("page"))
        assertEquals("record-first", more.url.queryParameter("firstItem")); assertEquals("record-last", more.url.queryParameter("lastItem"))
        back(); awaitTag("user-name:7"); compose.onNodeWithTag("user-fans:7").performClick(); awaitTag("user-connection:0")
        compose.onNodeWithText("TA的粉丝").assertIsDisplayed(); capture("after-fans")
        assertEquals(1, calls("/v6/user/fansList")); assertEquals(1, calls("/v6/user/space"))
        assertNull(store.state.value.activeId)
    }
    @Test fun childBackRetainsNonzeroOffsetRowsAndExistingProfileRead() {
        many = true; show("user:7"); awaitTag("user-name:7")
        compose.onNodeWithTag("user-following:7").performClick(); awaitTag("user-connection:0")
        compose.onNodeWithTag("user-connections-list").performScrollToIndex(12)
        val scroll = scroll(); assertTrue(scroll > 0f)
        val top = compose.onNodeWithTag("user-connection:12").fetchSemanticsNode().positionInRoot.y
        capture("before-child-user")
        compose.onNodeWithTag("user-connection:12").performClick(); awaitTag("user-name:20")
        back(); awaitTag("user-connection:12")
        assertEquals(scroll, scroll(), .001f)
        assertEquals(top, compose.onNodeWithTag("user-connection:12").fetchSemanticsNode().positionInRoot.y, 1f)
        assertEquals(1, calls("/v6/user/followList")); capture("after-child-back")
        back(); awaitTag("user-name:7"); assertEquals(2, calls("/v6/user/space"))
    }
    @Test fun threeRepeatedBoundaryPagesKeepEveryContactDestinationAndChildBackOffset() {
        repeatedPages = true; show("user-following:7"); awaitTag("user-connection:0")
        repeat(2) { page ->
            compose.onNodeWithTag("user-connections-list").performScrollToNode(hasTestTag("user-connections-more"))
            compose.onNodeWithTag("user-connections-more").performClick()
            compose.waitUntil(15000) {
                compose.onNodeWithTag("user-connections-list").fetchSemanticsNode()
                model.states.value["user-following:7"]?.items?.size == (page + 2) * 8
            }
        }
        val rows = model.states.value.getValue("user-following:7").items
        assertEquals(List(24) { "record-shared" }, rows.map { it.id })
        assertEquals((8..31).map(Int::toString), rows.map { it.userId })
        compose.onNodeWithTag("user-connections-more").assertDoesNotExist()
        for (index in listOf(0, 8, 16)) {
            compose.onNodeWithTag("user-connections-list").performScrollToIndex(index)
            val offset = scroll()
            if (index > 0) assertTrue(offset > 0f)
            val top = compose.onNodeWithTag("user-connection:$index").fetchSemanticsNode().positionInRoot.y
            if (index == 16) capture("repeated-pages-before-child")
            compose.onNodeWithTag("user-connection:$index").performClick(); awaitTag("user-name:${index + 8}")
            back(); awaitTag("user-connection:$index")
            assertEquals(offset, scroll(), .001f)
            assertEquals(top, compose.onNodeWithTag("user-connection:$index").fetchSemanticsNode().positionInRoot.y, 1f)
            assertEquals(rows, model.states.value.getValue("user-following:7").items)
            if (index == 16) capture("repeated-pages-after-child")
        }
        val pages = requests.filter { it.url.encodedPath == "/v6/user/followList" }
        assertEquals(listOf("1", "2", "3"), pages.map { it.url.queryParameter("page") })
        pages.drop(1).forEach {
            assertEquals("record-shared", it.url.queryParameter("firstItem"))
            assertEquals("record-shared", it.url.queryParameter("lastItem"))
        }
        assertEquals(listOf("8", "16", "24"), requests.filter { it.url.encodedPath == "/v6/user/space" }.map { it.url.queryParameter("uid") })
    }
    @Test fun failedNextPageKeepsRowsAndRetryUsesSameCursor() {
        moreFailure = true; show("user-fans:7"); awaitTag("user-connection:0")
        compose.onNodeWithTag("user-connections-list").performScrollToNode(hasTestTag("user-connections-more"))
        compose.onNodeWithTag("user-connections-more").performClick(); awaitText("重试")
        assertEquals(3, model.states.value.getValue("user-fans:7").items.size)
        compose.onNodeWithTag("user-connections-list").performScrollToNode(hasText("重试")); capture("after-next-error")
        compose.runOnIdle { moreFailure = false }; compose.onNodeWithText("重试").performClick(); awaitTag("user-connection:3")
        val pages = requests.filter { it.url.encodedPath == "/v6/user/fansList" }
        assertEquals(listOf("1", "2", "2"), pages.map { it.url.queryParameter("page") })
        assertEquals(pages[1].url, pages[2].url)
    }
    @Test fun initialFailureCanRetryToExplicitEmptyAndRefreshRemainsReadOnly() {
        firstFailure = true; show("user-following:7"); awaitText("重试"); capture("after-initial-error")
        compose.runOnIdle { firstFailure = false; empty = true }; compose.onNodeWithText("重试").performClick()
        awaitText("暂无内容"); capture("after-empty")
        compose.onNodeWithContentDescription("刷新用户列表").performClick()
        compose.waitUntil(15000) { calls("/v6/user/followList") == 3 }; awaitText("暂无内容")
        assertTrue(requests.all { it.method == "GET" })
    }
    @Test fun invalidRouteDoesNotDispatchOrEnableRefresh() {
        show("user-following:0"); awaitText("用户 ID 格式无效，未读取用户列表")
        compose.onNodeWithContentDescription("刷新用户列表").assertIsNotEnabled(); assertTrue(requests.isEmpty())
    }
    @Test fun unknownCountsAndPublicInfoAreNotInventedAndInfoDismissPreservesProfile() {
        missing = true; show("user:7"); awaitTag("user-name:7")
        compose.onNodeWithTag("user-following:7").assertTextEquals("关注")
        compose.onNodeWithTag("user-fans:7").assertTextEquals("粉丝")
        compose.onNodeWithTag("user-info:7").performClick(); awaitTag("user-info-dialog:7")
        compose.onNodeWithText("性别: 未知").assertIsDisplayed(); compose.onNodeWithText("注册时间: 未提供").assertIsDisplayed()
        capture("after-unknown-info", dialog = true); dialogBack(); compose.onNodeWithTag("user-info-dialog:7").assertDoesNotExist()
        compose.onNodeWithTag("user-name:7").assertIsDisplayed(); assertEquals(1, calls("/v6/user/space"))
    }
    @Test @Config(qualifiers = "w393dp-h640dp-420dpi") fun darkLargeTypeLinksAndInfoRemainReachableAndDismissible() {
        show("user:7", systemScale = 2f, dark = true); awaitTag("user-name:7"); awaitImages()
        compose.onNodeWithTag("content-list").performScrollToNode(hasTestTag("user-following:7"))
        for (tag in listOf("user-following:7", "user-fans:7")) compose.onNodeWithTag(tag).assertIsDisplayed()
        capture("after-dark-font2-links")
        compose.onNodeWithTag("user-info:7").performClick(); awaitTag("user-info-dialog:7")
        compose.onNodeWithText("注册时间:", substring = true).performScrollTo()
        compose.onNodeWithText("关闭").assertIsDisplayed(); capture("after-dark-font2-info", dialog = true)
        compose.onNodeWithText("关闭").performClick(); compose.onNodeWithTag("user-info-dialog:7").assertDoesNotExist()
        compose.onNodeWithTag("user-fans:7").performClick(); awaitTag("user-connection:0"); capture("after-dark-font2-fans")
    }
    @Test fun blockedConnectionTargetsStayFilteredWithoutChangingRawCursor() {
        memory.data.value = LocalState(blacklist = Blacklist(userBlackList = listOf("8")))
        show("user-fans:7"); awaitTag("user-connection:0")
        compose.onNodeWithText("合成联系人8").assertDoesNotExist()
        assertEquals("record-last", model.states.value.getValue("user-fans:7").next!!.lastItem)
        compose.onNodeWithText("合成联系人10").assertIsDisplayed()
    }
    @Test fun invalidContactUidNeverFallsBackToRelationshipOwnerOrUnrelatedUrl() {
        invalidContact = true; show("user-fans:7"); awaitTag("user-connection:0")
        compose.onNodeWithTag("user-connection:0").assertHasNoClickAction()
        compose.onAllNodesWithText("未提供可打开的内容地址").fetchSemanticsNodes().also { assertTrue(it.isNotEmpty()) }
        assertEquals(0, calls("/v6/user/space")); assertTrue(requests.all { it.url.encodedPath == "/v6/user/fansList" })
    }
    @Test fun accountSwitchReloadsSameExplicitPublicTargetAndDiscardsGuestRows() {
        show("user-fans:7"); awaitTag("user-connection:0")
        val guestModel = model
        compose.runOnIdle {
            val origin = "https://api.coolapk.com/".toHttpUrl()
            val cookies = listOf("uid=99", "token=synthetic-account", "SESSID=synthetic-session").map { Cookie.parse(origin, "$it; Domain=coolapk.com; Path=/; Secure")!! }
            assertTrue(store.saveAuthenticated("99", "Synthetic", "synthetic-device", cookies, store.snapshot()))
        }
        compose.waitUntil(15000) { calls("/v6/user/fansList") == 2 }; awaitText("账号联系人8")
        assertNotSame(guestModel, model); compose.onNodeWithText("合成联系人8").assertDoesNotExist()
        assertTrue(requests.all { it.url.queryParameter("uid") == "7" })
        assertTrue(requests.last().header("Cookie").orEmpty().contains("uid=99"))
        assertEquals("99", store.state.value.activeId)
    }
    @Test fun backCancelsVisibleReadChallengeAndReturnCanReadAgain() {
        show("user:7"); awaitTag("user-name:7"); compose.runOnIdle { challenge = true }
        compose.onNodeWithTag("user-following:7").performClick(); awaitText("重试")
        val pending = requireNotNull(model.states.value.getValue("user-following:7").failure?.pageRead)
        capture("after-verification-required"); back(); awaitTag("user-name:7")
        assertFalse(pending.acceptHumanProof("NEC:abcdefgh:synthetic-proof"))
        compose.runOnIdle { challenge = false }; compose.onNodeWithTag("user-following:7").performClick(); awaitTag("user-connection:0")
        assertEquals(2, calls("/v6/user/followList"))
    }
    @Test fun localFilterFailureNeverReadsOrDisplaysUnfilteredUsers() {
        localFailure = true; show("user-fans:7"); awaitText("本地过滤设置无法读取")
        compose.onNodeWithContentDescription("刷新用户列表").assertIsNotEnabled()
        assertTrue(requests.isEmpty()); compose.onNodeWithTag("user-connection:0").assertDoesNotExist()
    }
    @Test fun localFilterReadinessPrecedesRequestsAndInitialRowsAreAlreadyFiltered() {
        localPending = true; show("user-fans:7"); awaitText("正在读取本地过滤设置")
        compose.onNodeWithContentDescription("刷新用户列表").assertIsNotEnabled(); assertTrue(requests.isEmpty())
        compose.runOnIdle { localGate.value = LocalState(blacklist = Blacklist(userBlackList = listOf("8"))) }
        awaitTag("user-connection:0"); compose.onNodeWithText("合成联系人8").assertDoesNotExist()
        compose.onNodeWithText("合成联系人10").assertIsDisplayed(); assertEquals(1, calls("/v6/user/fansList"))
    }
    @Test fun localDisplayCapIsExplicitPreservesWireCursorAndPreventsFurtherDispatch() {
        capRows = true; show("user-following:7"); awaitTag("user-connection:0")
        repeat(4) { page ->
            compose.onNodeWithTag("user-connections-list").performScrollToNode(hasTestTag("user-connections-more"))
            compose.onNodeWithTag("user-connections-more").performClick()
            compose.waitUntil(15000) {
                compose.onNodeWithTag("user-connections-list").fetchSemanticsNode()
                model.states.value["user-following:7"]?.items?.size == (page + 2) * 400
            }
        }
        compose.onNodeWithTag("user-connections-list").performScrollToNode(hasTestTag("user-connections-limit"))
        compose.onNodeWithText("已加载 2000 条，达到本地展示上限；可刷新以更新列表。").assertIsDisplayed()
        compose.onNodeWithTag("user-connections-more").assertDoesNotExist(); capture("after-local-display-limit")
        val state = model.states.value.getValue("user-following:7")
        assertEquals("record-5-399", state.next!!.lastItem); assertTrue(state.displayLimitReached)
        compose.runOnIdle { model.load("user-following:7", more = true) }; compose.waitForIdle()
        assertEquals(5, calls("/v6/user/followList"))
    }
    @Test @Config(qualifiers = "w393dp-h640dp-420dpi") fun dialogUsesSystemTimesAppDensityExactlyOnceInEveryTextSlot() {
        show("user:7", scale = 1.2f, systemScale = 2f, dark = true); awaitTag("user-name:7")
        compose.onNodeWithTag("user-info:7").performClick(); awaitTag("user-info-dialog:7")
        capture("after-system2-app1.2-info", dialog = true)
        for (label in listOf("合成用户7", "UID: 7", "关闭")) {
            val layouts = mutableListOf<TextLayoutResult>()
            compose.onNode(hasText(label) and hasAnyAncestor(isDialog()), useUnmergedTree = true)
                .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            assertEquals("Effective density for $label", 2.4f, layouts.single().layoutInput.density.fontScale, .001f)
        }
        compose.onNodeWithText("注册时间:", substring = true).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("关闭").assertIsDisplayed(); capture("after-system2-app1.2-info-bottom", dialog = true)
        dialogBack(); compose.onNodeWithTag("user-info-dialog:7").assertDoesNotExist()
        compose.onNodeWithTag("user-name:7").assertIsDisplayed(); assertEquals(1, calls("/v6/user/space"))
    }
    @Test fun accountSwitchDismissesOpenInfoAndFreshProfileDoesNotReopenIt() {
        show("user:7"); awaitTag("user-name:7")
        compose.onNodeWithTag("user-info:7").performClick(); awaitTag("user-info-dialog:7")
        compose.runOnIdle {
            val origin = "https://api.coolapk.com/".toHttpUrl()
            val cookies = listOf("uid=99", "token=synthetic-account", "SESSID=synthetic-session").map { Cookie.parse(origin, "$it; Domain=coolapk.com; Path=/; Secure")!! }
            assertTrue(store.saveAuthenticated("99", "Synthetic", "synthetic-device", cookies, store.snapshot()))
        }
        compose.waitUntil(15000) { calls("/v6/user/space") == 2 }; awaitTag("user-name:7")
        compose.onNodeWithTag("user-info-dialog:7").assertDoesNotExist()
        compose.onNodeWithTag("user-name:7").assertIsDisplayed()
        compose.onNodeWithTag("user-info:7").performClick(); awaitTag("user-info-dialog:7")
        compose.onNodeWithText("关闭").performClick(); assertEquals(2, calls("/v6/user/space"))
    }
    private fun show(initialRoute: String, scale: Float = 1f, dark: Boolean = false, systemScale: Float = 1f) {
        compose.runOnUiThread {
            previousSystemFontScale = org.robolectric.RuntimeEnvironment.getFontScale()
            org.robolectric.RuntimeEnvironment.setFontScale(systemScale)
        }
        images = SyntheticFeedImageLoader(compose.activity)
        val container = AppContainer(compose.activity.application).also { it.nativeUpdates.enabled = false }
        val localStore = if (localFailure || localPending) object : JsonStateStore<LocalState> {
            override val data = if (localFailure) flow<LocalState> { throw IOException("synthetic local read failure") } else localGate.filterNotNull()
            override suspend fun update(transform: (LocalState) -> LocalState) = Unit
        } else memory
        AppContainer::class.java.getDeclaredField("local").apply { isAccessible = true }.set(container, LocalRepository(localStore))
        store = AccountStore(object : CredentialPersistence {
            override fun read(): ByteArray? = null
            override fun write(bytes: ByteArray) = Unit
            override fun reset() = Unit
        }, guestDeviceId = "synthetic-connections-device")
        client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request(); val path = request.url.encodedPath
            if (request.method != "GET" || request.url.host !in setOf("api.coolapk.com", "api2.coolapk.com") ||
                path !in setOf("/v6/user/space", "/v6/user/feedList", "/v6/user/followList", "/v6/user/fansList")) {
                unexpected += "${request.method} $path"; throw IOException("Sockets disabled in fixture")
            }
            requests += request
            val uid = request.url.queryParameter("uid") ?: "7"
            val page = request.url.queryParameter("page") ?: "1"
            val connection = path.endsWith("followList") || path.endsWith("fansList")
            val status = if (connection && (if (page == "1") firstFailure else moreFailure)) 503 else 200
            val body = when {
                path == "/v6/user/space" -> if (missing) """{"data":{"uid":"$uid"}}""" else """{"data":{"uid":"$uid","username":"合成用户$uid","userAvatar":"${SyntheticFeedImageLoader.AVATAR}","cover":"${SyntheticFeedImageLoader.LANDSCAPE}","level":6,"feed":24,"be_like_num":106,"follow":12,"fans":48,"gender":1,"regdate":1500000000,"logintime":1770000000}}"""
                connection && challenge -> """{"messageStatus":"err_request_captcha_v2","messageExtra":{"captchaId":"abcdefghijklmnop","captchaField":"_v2_post_token"}}"""
                connection && empty -> """{"data":[]}"""
                connection && repeatedPages -> """{"data":[${(0..7).joinToString(",") { contact("record-shared", "${8 + (page.toInt() - 1) * 8 + it}") }}${if (page == "3") ",{\"id\":\"record-shared\",\"entityType\":\"card\",\"entityTemplate\":\"noMoreDataCard\"}" else ""}]}"""
                connection && capRows -> """{"data":[${(0..399).joinToString(",") { contact("record-$page-$it", "${it + 8}") }}]}"""
                connection && page == "1" -> """{"data":[${if (many) (0..19).joinToString(",") { contact("record-$it", "${it + 8}") } else listOf(contact("record-first", "8"), contact("record-first", "8"), contact("record-last", "10")).joinToString(",")}]}"""
                connection -> """{"data":[${contact("record-next", "11")},{"id":"end","entityType":"card","entityTemplate":"noMoreDataCard"}]}"""
                else -> """{"data":[]}"""
            }
            val scopedBody = if (connection && request.header("Cookie").orEmpty().contains("uid=99")) body.replace("合成联系人", "账号联系人") else body
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(status).message("synthetic").body(scopedBody.toResponseBody()).build()
        }.build()
        val login = HttpLoginTransport(client)
        val repository = HttpCoolapkRepository(client = client, requestSession = { store.captureRequestSession() })
        val social = SocialRepository(store) { snapshot -> CoolapkHttpTransport(client = client, requestSession = { store.requestSessionFor(snapshot) }) }
        val ctor = AccountRuntime::class.java.getDeclaredConstructor(AccountStore::class.java, HttpLoginTransport::class.java,
            LoginCoordinator::class.java, HttpCoolapkRepository::class.java, SocialRepository::class.java).apply { isAccessible = true }
        val accounts = ctor.newInstance(store, login, LoginCoordinator(store, login, "synthetic-connections-device"), repository, social)
        compose.setContent {
            val state by store.state.collectAsState(); owner.select(state.generation)
            model = remember(state.generation) { ViewModelProvider(owner, viewModelFactory { initializer { BrowseViewModel(repository) } })[BrowseViewModel::class.java] }
            CompositionLocalProvider(LocalViewModelStoreOwner provides owner, LocalMediaImageLoader provides images.loader,
                LocalDensity provides Density(LocalDensity.current.density, systemScale * scale),
                LocalReadingStyles provides readingStyles(ReadingPreset.COMFORTABLE, FontChoice.NOTO_SANS)) {
                MaterialTheme(colorScheme = fixedColorScheme(0, dark), typography = appTypography(FontChoice.NOTO_SANS)) {
                    key(state.generation) { NativeApp(model, container, AppSettings(floatingNavigation = true, dynamicColor = false, fontScale = scale), accounts, state, initialRoute) }
                }
            }
        }
    }
    private fun contact(id: String, uid: String) = """{"id":"$id","entityId":"shared-entity","entityType":"contacts","uid":"777","url":"/feed/999","userInfo":{"uid":null,"username":"合成联系人$uid"},"fUserInfo":{"uid":"${if (invalidContact) "0" else uid}","username":"不应覆盖","userAvatar":"${SyntheticFeedImageLoader.AVATAR}","follow":3,"fans":4}}"""
    private fun calls(path: String) = requests.count { it.url.encodedPath == path }
    private fun scroll() = compose.onNodeWithTag("user-connections-list").fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()
    private fun awaitTag(tag: String) = compose.waitUntil(15000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
    private fun awaitText(text: String) = compose.waitUntil(15000) { compose.onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().isNotEmpty() }
    private fun awaitImages() = compose.waitUntil(15000) { images.loadedUrls.containsAll(setOf(SyntheticFeedImageLoader.AVATAR, SyntheticFeedImageLoader.LANDSCAPE)) }
    @Suppress("DEPRECATION")
    private fun dialogBack() = compose.runOnUiThread {
        // Physical Back goes to the foreground dialog window, not the activity dispatcher.
        requireNotNull(org.robolectric.shadows.ShadowDialog.getLatestDialog()).onBackPressed()
    }
    private fun back() = compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
    private fun capture(name: String, dialog: Boolean = false) {
        compose.waitForIdle()
        val bitmap = compose.runOnUiThread {
            val view = if (dialog) requireNotNull(org.robolectric.shadows.ShadowDialog.getLatestDialog()?.window?.decorView)
                else compose.activity.findViewById<View>(android.R.id.content)
            Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888).also { view.draw(Canvas(it)) }
        }
        File("build/verification/user-connections/$name.png").apply { parentFile!!.mkdirs() }.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
    }
}
