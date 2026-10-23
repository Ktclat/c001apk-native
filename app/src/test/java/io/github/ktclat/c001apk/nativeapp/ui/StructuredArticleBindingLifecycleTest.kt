// SPDX-License-Identifier: AGPL-3.0-only
package io.github.ktclat.c001apk.nativeapp.ui

import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import io.github.ktclat.c001apk.nativeapp.domain.*
import kotlinx.coroutines.CancellationException
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
import java.util.concurrent.atomic.AtomicReference

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class StructuredArticleBindingLifecycleTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private fun article(text: String, title: String? = null) = FullPostBody.Structured(
        """[{"type":"text","message":"$text"}]""", title, null, listOf(ArticleBlock.Text(text)))

    @Test fun equalImmutableArticleReplacementKeepsStateAndDoesNotReparse() {
        val original = article("SAME_ARTICLE", "same title")
        val body = mutableStateOf<FullPostBody>(original, neverEqualPolicy())
        val revision = mutableIntStateOf(0)
        val calls = AtomicInteger()
        val observed = AtomicReference<State<PreparedDetailReader?>?>()
        val parser: (FullPostBody, () -> Unit) -> PreparedFullPost = { input, active -> calls.incrementAndGet(); prepareFullPost(input, active) }
        compose.setContent {
            Column { Probe(body.value, 0, parser) { observed.set(it) }; Text("revision:${revision.intValue}") }
        }
        awaitText("same titleSAME_ARTICLE")
        val originalState = observed.get()
        val replacement = original.copy(rawOutput = String(original.rawOutput.toCharArray()), title = String(original.title!!.toCharArray()), blocks = original.blocks.toList())
        assertNotSame(original, replacement); assertEquals(original, replacement)
        compose.runOnIdle { body.value = replacement; revision.intValue++ }
        awaitText("revision:1")
        compose.onNodeWithText("same titleSAME_ARTICLE").assertExists()
        compose.onAllNodesWithText("pending").assertCountEquals(0)
        assertSame(originalState, observed.get()); assertEquals(1, calls.get())
        assertEquals(readerArticleDigest(original), observed.get()!!.value!!.bodyDigest)
    }

    @Test fun sourceReplacementImmediatelyClearsPendingAndOldNonCooperativeParserCannotPublish() {
        val body = mutableStateOf<FullPostBody>(article("OLD"))
        val entered = CountDownLatch(1); val release = CountDownLatch(1); val returned = CountDownLatch(1)
        val blockedState = AtomicReference<State<PreparedDetailReader?>?>()
        val parser: (FullPostBody, () -> Unit) -> PreparedFullPost = { input, active ->
            if ((input as? FullPostBody.Structured)?.title == "BLOCKED_TITLE") {
                entered.countDown(); check(release.await(15, TimeUnit.SECONDS)); returned.countDown()
                // Deliberately finish bounded framework-like work without consulting cancellation.
                prepareFullPost(input)
            } else prepareFullPost(input, active)
        }
        try {
            compose.setContent {
                val current = body.value
                Probe(current, 0, parser) { if ((current as? FullPostBody.Structured)?.title == "BLOCKED_TITLE") blockedState.set(it) }
            }
            awaitText("OLD")
            compose.runOnIdle { body.value = article("OLD", "BLOCKED_TITLE") }
            compose.onNodeWithText("pending").assertExists(); compose.onNodeWithText("digest:none").assertExists()
            compose.onAllNodesWithText("OLD").assertCountEquals(0); awaitGate(entered)
            compose.runOnIdle { body.value = FullPostBody.Available("ORDINARY_REPLACEMENT") }
            awaitText("ORDINARY_REPLACEMENT"); awaitText("digest:${readerBodyDigest("ORDINARY_REPLACEMENT")}")
            release.countDown(); awaitGate(returned); compose.waitForIdle()
            assertNull(blockedState.get()!!.value)
            compose.onAllNodesWithText("BLOCKED_TITLE", substring = true).assertCountEquals(0)
            compose.runOnIdle { body.value = article("LATEST_ARTICLE") }
            awaitText("LATEST_ARTICLE"); awaitText("digest:${readerArticleDigest(article("LATEST_ARTICLE"))}")
            compose.onAllNodesWithText("ORDINARY_REPLACEMENT").assertCountEquals(0)
        } finally { release.countDown() }
    }

    @Test fun articleParsingAndDigestAreOffMainBeforeMainPublicationForTheSameInput() {
        val source = article("WORKER_ARTICLE", "title")
        val checkpoints = AtomicInteger(); val publications = AtomicInteger()
        val parser: (FullPostBody, () -> Unit) -> PreparedFullPost = { input, active ->
            assertNotSame(Looper.getMainLooper(), Looper.myLooper())
            readerArticleDigest(input as FullPostBody.Structured) {
                assertNotSame(Looper.getMainLooper(), Looper.myLooper()); checkpoints.incrementAndGet(); active()
            }
            prepareFullPost(input, active)
        }
        compose.setContent {
            Probe(source, 0, parser) { state ->
                if (state.value != null) { assertSame(Looper.getMainLooper(), Looper.myLooper()); publications.incrementAndGet() }
            }
        }
        awaitText("titleWORKER_ARTICLE"); awaitText("digest:${readerArticleDigest(source)}")
        assertTrue(checkpoints.get() > 0); assertTrue(publications.get() > 0)
    }

    @Test fun unavailableVariantsPublishNoOldArticleOrDigestAndRetryRuntimeErrorCanRecover() {
        val body = mutableStateOf<FullPostBody>(article("ORIGINAL_ARTICLE"))
        val attempt = mutableIntStateOf(0)
        val failNext = AtomicBoolean(false)
        val observed = AtomicReference<State<PreparedDetailReader?>?>()
        val parser: (FullPostBody, () -> Unit) -> PreparedFullPost = { input, active ->
            if (failNext.getAndSet(false)) throw IllegalStateException("Synthetic bounded parser error")
            prepareFullPost(input, active)
        }
        compose.setContent { Probe(body.value, attempt.intValue, parser) { observed.set(it) } }
        awaitText("ORIGINAL_ARTICLE")
        for (limit in listOf(FullPostBodyLimit.MALFORMED_ARTICLE, FullPostBodyLimit.UNSUPPORTED_ARTICLE, FullPostBodyLimit.ARTICLE_COMPLEXITY)) {
            compose.runOnIdle { body.value = FullPostBody.Unavailable(limit) }
            awaitText(fullPostLimitMessage(limit)); compose.onNodeWithText("digest:none").assertExists()
            compose.onAllNodesWithText("ORIGINAL_ARTICLE").assertCountEquals(0)
            assertNull(observed.get()!!.value!!.bodyDigest)
        }
        compose.runOnIdle { failNext.set(true); body.value = article("RECOVERED_ARTICLE") }
        awaitText(fullPostLimitMessage(FullPostBodyLimit.PARSING_ERROR)); compose.onNodeWithText("digest:none").assertExists()
        compose.runOnIdle { attempt.intValue++ }
        awaitText("RECOVERED_ARTICLE"); awaitText("digest:${readerArticleDigest(article("RECOVERED_ARTICLE"))}")
    }

    @Test fun cancelledArticlePreparationCannotBecomeUnavailableAndRetryStartsFresh() {
        val attempt = mutableIntStateOf(0); val calls = AtomicInteger(); val cancelled = AtomicBoolean()
        val observed = AtomicReference<State<PreparedDetailReader?>?>()
        val parser: (FullPostBody, () -> Unit) -> PreparedFullPost = { input, active ->
            if (calls.incrementAndGet() == 1) { cancelled.set(true); throw CancellationException("synthetic article cancellation") }
            prepareFullPost(input, active)
        }
        compose.setContent { Probe(article("AFTER_CANCEL"), attempt.intValue, parser) { observed.set(it) } }
        compose.waitUntil(15_000) { compose.onRoot().fetchSemanticsNode(); cancelled.get() }
        compose.waitForIdle(); assertNull(observed.get()!!.value)
        compose.onNodeWithText("pending").assertExists(); compose.onNodeWithText("digest:none").assertExists()
        compose.onAllNodesWithText(fullPostLimitMessage(FullPostBodyLimit.PARSING_ERROR)).assertCountEquals(0)
        compose.runOnIdle { attempt.intValue++ }; awaitText("AFTER_CANCEL"); assertEquals(2, calls.get())
    }

    @Test fun frameworkErrorIsNotCaughtAndDisguisedAsARecoverableArticleFailure() {
        val source = article("source")
        val error = AssertionError("synthetic fatal framework Error")
        val publications = AtomicInteger()
        val result = mutableStateOf("pending")
        compose.setContent {
            LaunchedEffect(Unit) {
                try {
                    parseAndPublishFullPost(source, parser = { _, _ -> throw error }) { publications.incrementAndGet() }
                    result.value = "incorrectly swallowed"
                } catch (actual: AssertionError) {
                    assertTrue(generateSequence<Throwable>(actual) { it.cause }.take(8).any { it === error })
                    result.value = "Error propagated without publication"
                }
            }
            Text(result.value)
        }
        awaitText("Error propagated without publication"); assertEquals(0, publications.get())
    }

    @Composable private fun Probe(body: FullPostBody, attempt: Int, parser: (FullPostBody, () -> Unit) -> PreparedFullPost,
        observe: (State<PreparedDetailReader?>) -> Unit = {}) {
        val state = rememberPreparedDetailReader(body, attempt, parser)
        val result = state.value
        SideEffect { observe(state) }
        Column {
            when (val value = result?.prepared) {
                null -> Text("pending")
                is PreparedFullPost.Ready -> Text(value.pages.joinToString("") { it.text })
                is PreparedFullPost.Article -> Text(value.pages.flatMap { it.fragments }.filterIsInstance<ArticleFragment.RichText>().joinToString("") { it.value.text })
                is PreparedFullPost.Unavailable -> Text(fullPostLimitMessage(value.limit, value.receivedUnits))
            }
            Text("digest:${result?.bodyDigest ?: "none"}")
        }
    }
    private fun awaitText(value: String) = compose.waitUntil(15_000) { compose.onAllNodesWithText(value).fetchSemanticsNodes().isNotEmpty() }
    private fun awaitGate(gate: CountDownLatch) = compose.waitUntil(15_000) { compose.onRoot().fetchSemanticsNode(); gate.count == 0L }
}
