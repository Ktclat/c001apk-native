// SPDX-License-Identifier: AGPL-3.0-only
package io.github.ktclat.c001apk.nativeapp.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.LocalTextStyle
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import io.github.ktclat.c001apk.nativeapp.account.*
import io.github.ktclat.c001apk.nativeapp.data.*
import io.github.ktclat.c001apk.nativeapp.domain.*
import io.github.ktclat.c001apk.nativeapp.local.*
import io.github.ktclat.c001apk.nativeapp.social.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.*
import okhttp3.Cookie
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.lang.reflect.Proxy

/** New summary-only scope. All records/accounts are synthetic; API reads are loopback or a fake
 * public repository. No send, HTML-link activation, image-network substitution or real API is used.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w393dp-h852dp-420dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LiteralSummaryEmoticonTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private var server: MockWebServer? = null
    @After fun close() { server?.shutdown() }
    private fun layout(node: SemanticsNodeInteraction): TextLayoutResult {
        val results = mutableListOf<TextLayoutResult>()
        node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { assertTrue(it(results)) }
        return results.single()
    }
    private fun assertLocalOnly(node: SemanticsNodeInteraction, raw: String, count: Int): TextLayoutResult {
        node.assertTextEquals(raw)
        val result = layout(node)
        assertEquals(count, result.layoutInput.placeholders.size)
        assertTrue(result.layoutInput.text.getLinkAnnotations(0, raw.length).isEmpty())
        return result
    }

    @Test fun literalWrapperDoesNotParseMarkupOrUrlsAndPreservesOverflowArguments() {
        val raw = "<a href='/feed/42'>[笑哭]</a>[未知] https://example.test " + "长摘要".repeat(80)
        compose.setContent { MaterialTheme {
            NativeTokenText(raw, Modifier.width(180.dp).testTag("literal"), maxLines = 2, overflow = TextOverflow.Ellipsis)
        } }
        val value = assertLocalOnly(compose.onNodeWithTag("literal"), raw, 1)
        assertEquals(2, value.layoutInput.maxLines)
        assertEquals(TextOverflow.Ellipsis, value.layoutInput.overflow)
        assertTrue(value.lineCount <= 2)
        assertTrue(value.isLineEllipsized(value.lineCount - 1))
    }

    @Test fun actualHomeMessageTemplateRetainsSecondaryInkAndNoDestination() {
        val raw = "消息[受虐滑稽][笑哭][未知]<b>原样</b>"
        var ink = androidx.compose.ui.graphics.Color.Unspecified
        compose.setContent { MaterialTheme {
            ink = MaterialTheme.colorScheme.onSecondaryContainer
            HomeTemplateCard(ContentItem("message", "card", cardFields = HomeCardFields(description = raw)),
                HomeCardTemplate.MESSAGE, { error("Message text must not open a URL") }, { error("No refresh action") })
        } }
        val value = assertLocalOnly(compose.onNodeWithText(raw), raw, 2)
        assertEquals(ink, value.layoutInput.style.color)
        compose.onNodeWithText(raw).performTouchInput { click() }
    }

    @Test fun actualLocalFollowSummaryKeepsEightLineEllipsisAndOriginalParentRoute() {
        val raw = "[笑哭]" + "正文".repeat(1200)
        val state = object : JsonStateStore<LocalState> {
            override val data = MutableStateFlow(LocalState(follows = listOf(LocalFollowUser("42"))))
            override suspend fun update(transform: (LocalState) -> LocalState) { data.value = transform(data.value) }
        }
        var reads = 0
        val repository = Proxy.newProxyInstance(CoolapkRepository::class.java.classLoader, arrayOf(CoolapkRepository::class.java)) { proxy, method, _ ->
            when (method.name) {
                "chronologicalUserFeed" -> { reads++; ApiResult.Success(Page(listOf(ContentItem("7", "feed", title = "标题[受虐滑稽]", message = raw, userId = "42", userName = "作者")), null)) }
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> false
                "toString" -> "SyntheticLocalSummaryRepository"
                else -> error("Unexpected repository operation ${method.name}")
            }
        } as CoolapkRepository
        val opened = mutableListOf<String>()
        compose.setContent { MaterialTheme { LocalFollowFeedScreen(LocalRepository(state), repository,
            { opened += "feed:$it" }, { opened += "user:$it" }, {}) } }
        compose.waitUntil(10_000) { compose.onAllNodesWithText(raw).fetchSemanticsNodes().size == 1 }
        val node = compose.onNodeWithText(raw)
        val value = assertLocalOnly(node, raw, 1)
        assertEquals(8, value.layoutInput.maxLines)
        assertEquals(TextOverflow.Ellipsis, value.layoutInput.overflow)
        assertEquals(8, value.lineCount)
        assertTrue(value.isLineEllipsized(7))
        node.performTouchInput { click(requireNotNull(value.placeholderRects.single()).center) }
        compose.runOnIdle { assertEquals(listOf("feed:7"), opened); assertEquals(1, reads) }
    }

    @Test fun unsupportedNotificationGetsLocalArtButNoOpenActionOrNewRequest() {
        val raw = "合成通知[笑哭][未知]"
        val (repository, generation) = socialFixture(raw, conversation = false)
        val routes = mutableListOf<String>()
        compose.setContent { MaterialTheme { NotificationScreen(repository, generation, onNavigate = { routes += it }) } }
        compose.waitUntil(10_000) { compose.onAllNodesWithText(raw).fetchSemanticsNodes().size == 1 }
        assertLocalOnly(compose.onNodeWithText(raw), raw, 1)
        compose.onAllNodesWithText("打开动态").assertCountEquals(0)
        compose.onAllNodesWithText("查看相关动态").assertCountEquals(0)
        compose.onNodeWithText(raw).performTouchInput { click() }
        compose.runOnIdle { assertTrue(routes.isEmpty()); assertEquals(1, server!!.requestCount) }
        assertEquals("GET", server!!.takeRequest().method)
    }

    @Test fun privateSummaryRemainsLiteralAndOnlyExistingConversationButtonNavigates() {
        val raw = "<a href='/feed/99'>私信[笑哭]</a>[未知]"
        val (repository, generation) = socialFixture(raw, conversation = true)
        val opened = mutableListOf<String>()
        compose.setContent { MaterialTheme { NotificationScreen(repository, generation,
            onConversation = { key, uid -> opened += "$key:$uid" }, initialCategory = NoticeCategory.MESSAGES) } }
        compose.waitUntil(10_000) { compose.onAllNodesWithText(raw).fetchSemanticsNodes().size == 1 }
        assertLocalOnly(compose.onNodeWithText(raw), raw, 1)
        compose.onNodeWithText(raw).performTouchInput { click() }
        compose.runOnIdle { assertTrue(opened.isEmpty()) }
        compose.onNodeWithText("打开会话").performClick()
        compose.runOnIdle { assertEquals(listOf("synthetic-conversation:42"), opened); assertEquals(1, server!!.requestCount) }
        val request = server!!.takeRequest()
        assertEquals("GET", request.method)
        assertTrue(request.path!!.startsWith("/v6/message/list"))
    }

    private fun socialFixture(message: String, conversation: Boolean): Pair<SocialRepository, Long> {
        val endpoint = MockWebServer().also { it.start(); server = it }
        val accounts = AccountStore(object : CredentialPersistence {
            private var bytes: ByteArray? = null
            override fun read() = bytes?.copyOf()
            override fun write(bytes: ByteArray) { this.bytes = bytes.copyOf() }
            override fun reset() { bytes = null }
        })
        accounts.saveAuthenticated("1", "synthetic", "synthetic-device", listOf(Cookie.Builder().name("token")
            .value("synthetic-not-a-real-token").domain("coolapk.com").path("/").secure()
            .expiresAt(System.currentTimeMillis() + 3_600_000).build()), accounts.snapshot())
        endpoint.enqueue(MockResponse().setBody(buildJsonObject { put("data", buildJsonArray {
            add(buildJsonObject { put("id", "7"); put("entityType", if (conversation) "feed" else "unsupportedSynthetic"); put("message", message)
                put("username", "合成作者"); put("uid", "42")
                if (conversation) put("ukey", "synthetic-conversation") })
            add(buildJsonObject { put("entityType", "noMoreDataCard") })
        }) }.toString()))
        val repository = SocialRepository(accounts) { captured -> CoolapkHttpTransport(
            endpoints = ApiEndpoints(endpoint.url("/"), endpoint.url("/")),
            requestSession = { RequestSession(cookieFor = { null }, isCurrent = { accounts.isCurrent(captured) },
                acceptCookies = { _, _ -> }, deviceCode = captured.deviceId,
                isSessionCurrent = { accounts.isCurrentRead(captured) }, isReadSessionCurrent = { accounts.isCurrentRead(captured) }) }) }
        return repository to accounts.state.value.generation
    }
}
