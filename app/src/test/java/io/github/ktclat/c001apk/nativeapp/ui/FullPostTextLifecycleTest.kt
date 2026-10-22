package io.github.ktclat.c001apk.nativeapp.ui

import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import io.github.ktclat.c001apk.nativeapp.domain.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class FullPostTextLifecycleTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @Test fun realDetailTailIsReachableAndRemainingPageStateIsExplicit() {
        val html = "x".repeat(12_000) + "FULL_DETAIL_TAIL"
        compose.setContent { MaterialTheme { Column(Modifier.verticalScroll(rememberScrollState())) {
            FullPostText(FullPostBody.Available(html), "x".repeat(12_000), {}, null)
        } } }
        awaitText("接口正文已完整载入 · 分段阅读 · 第 1/4 段 · 剩余 3 段")
        compose.onAllNodesWithText("FULL_DETAIL_TAIL").assertCountEquals(0)
        repeat(3) { compose.onNodeWithText("下一段").performScrollTo().performClick() }
        compose.onNodeWithTag("full-post-page").assertTextEquals("FULL_DETAIL_TAIL")
        compose.onNodeWithTag("full-post-progress").assertTextEquals("接口正文已完整载入 · 分段阅读 · 第 4/4 段 · 剩余 0 段")
        compose.onNodeWithText("已到正文末尾").assertExists()
        compose.onNodeWithText("下一段").assertIsNotEnabled()
    }
    @Test fun oversizeAndUnsupportedDoNotAdvertiseFullAvailability() {
        val body = mutableStateOf<FullPostBody>(FullPostBody.Unavailable(FullPostBodyLimit.BODY_TOO_LARGE, 262145))
        val opened = AtomicInteger()
        compose.setContent { MaterialTheme { Column(Modifier.verticalScroll(rememberScrollState())) {
            FullPostText(body.value, "preview", {}, { opened.incrementAndGet() })
        } } }
        awaitText(fullPostLimitMessage(FullPostBodyLimit.BODY_TOO_LARGE, 262145))
        compose.onAllNodesWithText("接口正文已完整载入", substring = true).assertCountEquals(0)
        compose.onAllNodesWithText("阅读完整内容").assertCountEquals(0)
        compose.onNodeWithText("浏览器打开原文").performScrollTo().performClick()
        assertEquals(1, opened.get())
        compose.runOnIdle { body.value = FullPostBody.Unavailable(FullPostBodyLimit.STRUCTURED_ARTICLE) }
        awaitText(fullPostLimitMessage(FullPostBodyLimit.STRUCTURED_ARTICLE))
        compose.onAllNodesWithText("接口正文已完整载入", substring = true).assertCountEquals(0)
    }
    @Test fun detailReplacementClearsOldPageAndResetsToFirstSegment() {
        val body = mutableStateOf<FullPostBody>(FullPostBody.Available("x".repeat(8000) + "OLD_TAIL"))
        compose.setContent { MaterialTheme { Column(Modifier.verticalScroll(rememberScrollState())) {
            FullPostText(body.value, "", {}, null)
        } } }
        awaitText("接口正文已完整载入 · 分段阅读 · 第 1/3 段 · 剩余 2 段")
        repeat(2) { compose.onNodeWithText("下一段").performScrollTo().performClick() }
        compose.onNodeWithTag("full-post-page").assertTextEquals("OLD_TAIL")
        compose.runOnIdle { body.value = FullPostBody.Available("NEW_BODY") }
        awaitText("NEW_BODY")
        compose.onAllNodesWithText("OLD_TAIL").assertCountEquals(0)
        compose.onNodeWithTag("full-post-progress").assertTextEquals("接口正文已完整载入 · 分段阅读 · 第 1/1 段 · 剩余 0 段")
    }
    @Test fun parserExceptionLeavesPendingOffersFallbackAndCanRetry() {
        val attempts = AtomicInteger()
        val opened = AtomicInteger()
        val parser: (FullPostBody, () -> Unit) -> PreparedFullPost = { body, active ->
            if (attempts.incrementAndGet() == 1) throw IllegalArgumentException("Synthetic malformed HTML")
            prepareFullPost(body, active)
        }
        compose.setContent { MaterialTheme { Column(Modifier.verticalScroll(rememberScrollState())) {
            FullPostText(FullPostBody.Available("RECOVERED_BODY"), "preview", {}, { opened.incrementAndGet() }, parser)
        } } }
        awaitText(fullPostLimitMessage(FullPostBodyLimit.PARSING_ERROR))
        compose.onNodeWithTag("full-post-progress").assertDoesNotExist()
        compose.onAllNodesWithText("正在排版详情正文…").assertCountEquals(0)
        compose.onNodeWithText("浏览器打开原文").performScrollTo().performClick()
        assertEquals(1, opened.get())
        compose.onNodeWithText("重新排版正文").performScrollTo().performClick()
        awaitText("RECOVERED_BODY")
        assertEquals(2, attempts.get())
        compose.onNodeWithTag("full-post-limit").assertDoesNotExist()
    }
    @Test fun actualParserIsOffMainAndOnlyPublicationIsMain() {
        val published = mutableStateOf("pending")
        val entered = AtomicBoolean()
        compose.setContent {
            LaunchedEffect(Unit) {
                parseAndPublishFullPost(FullPostBody.Available("<b>full</b>"), parser = { body, active ->
                    assertNotSame(Looper.getMainLooper(), Looper.myLooper())
                    entered.set(true)
                    prepareFullPost(body, active)
                }) {
                    assertSame(Looper.getMainLooper(), Looper.myLooper())
                    published.value = (it as PreparedFullPost.Ready).pages.single().text
                }
            }
            Text(published.value)
        }
        awaitText("full")
        assertTrue(entered.get())
    }
    @Test fun replacedInputImmediatelyClearsAndNonCooperativeOldParserCannotPublish() {
        val body = mutableStateOf<FullPostBody>(FullPostBody.Available("old"))
        val entered = AtomicBoolean()
        val returned = AtomicBoolean()
        val release = CountDownLatch(1)
        val parser: (FullPostBody, () -> Unit) -> PreparedFullPost = { input, active ->
            val html = (input as FullPostBody.Available).html
            if (html == "blocked") {
                entered.set(true)
                check(release.await(20, TimeUnit.SECONDS))
                returned.set(true)
                // Deliberately ignores the active callback to model non-cooperative framework code.
                PreparedFullPost.Ready(listOf(FullPostPage("blocked", emptyList())))
            } else prepareFullPost(input, active)
        }
        compose.setContent {
            val parsed by rememberFullPost(body.value, parser)
            Text((parsed as? PreparedFullPost.Ready)?.pages?.single()?.text ?: "pending")
        }
        try {
            awaitText("old")
            compose.runOnIdle { body.value = FullPostBody.Available("blocked") }
            compose.onNodeWithText("pending").assertExists()
            compose.onAllNodesWithText("old").assertCountEquals(0)
            compose.waitUntil(15_000) { entered.get() }
            compose.runOnIdle { body.value = FullPostBody.Available("latest") }
            awaitText("latest")
            release.countDown()
            compose.waitUntil(15_000) { returned.get() }
            compose.waitForIdle()
            compose.onNodeWithText("latest").assertExists()
            compose.onAllNodesWithText("blocked").assertCountEquals(0)
        } finally { release.countDown() }
    }
    @Test fun accountGenerationDisposalPreventsOldSameBodyPublication() {
        val generation = mutableStateOf(1)
        val publication = mutableStateOf(0)
        val entered = AtomicBoolean()
        val finished = AtomicBoolean()
        val oldPublications = AtomicInteger()
        val newPublications = AtomicInteger()
        val release = CountDownLatch(1)
        try {
            compose.setContent {
                key(generation.value) {
                    val captured = generation.value
                    LaunchedEffect(Unit) {
                        try {
                            parseAndPublishFullPost(FullPostBody.Available("same detail"), parser = { input, active ->
                                if (captured == 1) { entered.set(true); check(release.await(20, TimeUnit.SECONDS)) }
                                prepareFullPost(input, active)
                            }) {
                                if (captured == 1) oldPublications.incrementAndGet() else newPublications.incrementAndGet()
                                publication.value = captured
                            }
                        } finally { if (captured == 1) finished.set(true) }
                    }
                    Column { Text("generation:$captured"); Text("publication:${publication.value}") }
                }
            }
            compose.waitUntil(15_000) { entered.get() }
            compose.runOnIdle { generation.value = 2 }
            compose.onNodeWithText("generation:2").assertExists()
            // Synchronize the Main publication through its rendered result, not an atomic-only poll.
            awaitText("publication:2")
            assertEquals(1, newPublications.get())
            release.countDown()
            compose.waitUntil(15_000) { compose.onRoot().fetchSemanticsNode(); finished.get() }
            compose.onNodeWithText("publication:2").assertExists()
            assertEquals(1, newPublications.get())
            assertEquals(0, oldPublications.get())
        } finally { release.countDown() }
    }
    private fun awaitText(text: String) {
        compose.waitUntil(15_000) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
    }
}
