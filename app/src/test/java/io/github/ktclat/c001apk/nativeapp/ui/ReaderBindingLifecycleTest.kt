// SPDX-License-Identifier: AGPL-3.0-only
package io.github.ktclat.c001apk.nativeapp.ui

import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import io.github.ktclat.c001apk.nativeapp.domain.FullPostBody
import io.github.ktclat.c001apk.nativeapp.domain.FullPostBodyBounds
import io.github.ktclat.c001apk.nativeapp.domain.FullPostBodyLimit
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
class ReaderBindingLifecycleTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun equalBodyValueReplacementKeepsPreparedStateWithoutRestartingTheParser() {
        val original = FullPostBody.Available("SAME_BODY")
        val body = mutableStateOf<FullPostBody>(original, neverEqualPolicy())
        val revision = mutableIntStateOf(0)
        val calls = AtomicInteger()
        val observed = AtomicReference<State<PreparedDetailReader?>?>()
        val parser: (FullPostBody, () -> Unit) -> PreparedFullPost = { input, active ->
            calls.incrementAndGet()
            prepareFullPost(input, active)
        }
        compose.setContent {
            Column {
                PreparedProbe(body.value, 0, parser) { observed.set(it) }
                Text("revision:${revision.intValue}")
            }
        }
        awaitText("SAME_BODY")
        val preparedState = observed.get()
        val replacement = FullPostBody.Available(String(original.html.toCharArray()))
        assertNotSame(original, replacement)
        assertEquals(original, replacement)
        compose.runOnIdle { body.value = replacement; revision.intValue++ }
        awaitText("revision:1")
        compose.onNodeWithText("SAME_BODY").assertExists()
        compose.onAllNodesWithText("pending").assertCountEquals(0)
        assertSame(preparedState, observed.get())
        assertEquals(1, calls.get())
        assertEquals(readerBodyDigest(original.html), observed.get()!!.value!!.bodyDigest)
    }

    @Test fun changedInputImmediatelyClearsAndNonCooperativeOldParserCannotPublishAfterReplacement() {
        val body = mutableStateOf<FullPostBody>(FullPostBody.Available("old"))
        val entered = AtomicBoolean()
        val returned = AtomicBoolean()
        val blockedState = AtomicReference<State<PreparedDetailReader?>?>()
        val release = CountDownLatch(1)
        val parser: (FullPostBody, () -> Unit) -> PreparedFullPost = { input, active ->
            if ((input as FullPostBody.Available).html == "blocked") {
                entered.set(true)
                check(release.await(15, TimeUnit.SECONDS))
                returned.set(true)
                // Deliberately does not call active, like a bounded non-cooperative framework parser.
                PreparedFullPost.Ready(listOf(FullPostPage("blocked", emptyList())))
            } else prepareFullPost(input, active)
        }
        try {
            compose.setContent {
                val input = body.value
                PreparedProbe(input, 0, parser) { if ((input as FullPostBody.Available).html == "blocked") blockedState.set(it) }
            }
            awaitText("old")
            compose.runOnIdle { body.value = FullPostBody.Available("blocked") }
            compose.onNodeWithText("pending").assertExists()
            compose.onNodeWithText("digest:none").assertExists()
            compose.onAllNodesWithText("old").assertCountEquals(0)
            awaitSignal(entered)
            compose.runOnIdle { body.value = FullPostBody.Available("latest") }
            awaitText("latest")
            awaitText("digest:${readerBodyDigest("latest")}")
            release.countDown()
            awaitSignal(returned)
            compose.waitForIdle()
            compose.runOnIdle { assertNull(blockedState.get()!!.value) }
            compose.onNodeWithText("latest").assertExists()
            compose.onAllNodesWithText("blocked").assertCountEquals(0)
            compose.onAllNodesWithText(fullPostLimitMessage(FullPostBodyLimit.PARSING_ERROR)).assertCountEquals(0)
        } finally { release.countDown() }
    }

    @Test fun ownerGenerationDisposalPreventsAnOldNonCooperativeSameBodyPublication() {
        val body = FullPostBody.Available("same body for both owners")
        val generation = mutableIntStateOf(1)
        val calls = AtomicInteger()
        val entered = AtomicBoolean()
        val returned = AtomicBoolean()
        val oldState = AtomicReference<State<PreparedDetailReader?>?>()
        val newState = AtomicReference<State<PreparedDetailReader?>?>()
        val release = CountDownLatch(1)
        val parser: (FullPostBody, () -> Unit) -> PreparedFullPost = { _, _ ->
            val call = calls.incrementAndGet()
            if (call == 1) {
                entered.set(true)
                check(release.await(15, TimeUnit.SECONDS))
                returned.set(true)
            }
            PreparedFullPost.Ready(listOf(FullPostPage(if (call == 1) "old generation result" else "new generation result", emptyList())))
        }
        try {
            compose.setContent {
                key(generation.intValue) {
                    val capturedGeneration = generation.intValue
                    Column {
                        Text("generation:$capturedGeneration")
                        PreparedProbe(body, 0, parser) {
                            if (capturedGeneration == 1) oldState.set(it) else newState.set(it)
                        }
                    }
                }
            }
            awaitSignal(entered)
            compose.runOnIdle { generation.intValue = 2 }
            awaitText("generation:2")
            awaitText("new generation result")
            awaitText("digest:${readerBodyDigest(body.html)}")
            assertEquals(2, calls.get())
            release.countDown()
            awaitSignal(returned)
            compose.waitForIdle()
            compose.runOnIdle {
                assertNull(oldState.get()!!.value)
                assertNotNull(newState.get()!!.value)
            }
            compose.onNodeWithText("new generation result").assertExists()
            compose.onAllNodesWithText("old generation result").assertCountEquals(0)
            assertEquals(2, calls.get())
        } finally { release.countDown() }
    }

    @Test fun changingParserIdentityClearsPreparedStateEvenForTheSameBodyAndAttempt() {
        val body = FullPostBody.Available("source")
        val firstCalls = AtomicInteger()
        val secondCalls = AtomicInteger()
        val entered = AtomicBoolean()
        val release = CountDownLatch(1)
        val first: (FullPostBody, () -> Unit) -> PreparedFullPost = { _, active ->
            active(); firstCalls.incrementAndGet()
            PreparedFullPost.Ready(listOf(FullPostPage("first parser", emptyList())))
        }
        val second: (FullPostBody, () -> Unit) -> PreparedFullPost = { _, active ->
            secondCalls.incrementAndGet(); entered.set(true)
            check(release.await(15, TimeUnit.SECONDS)); active()
            PreparedFullPost.Ready(listOf(FullPostPage("second parser", emptyList())))
        }
        val parser = mutableStateOf(first)
        try {
            compose.setContent { PreparedProbe(body, 0, parser.value) }
            awaitText("first parser")
            compose.runOnIdle { parser.value = second }
            compose.onNodeWithText("pending").assertExists()
            compose.onNodeWithText("digest:none").assertExists()
            compose.onAllNodesWithText("first parser").assertCountEquals(0)
            awaitSignal(entered)
            release.countDown()
            awaitText("second parser")
            assertEquals(1, firstCalls.get())
            assertEquals(1, secondCalls.get())
        } finally { release.countDown() }
    }

    @Test fun parserAndExactDigestWorkOnWorkerDispatcherBeforeMainObservedPublication() {
        val body = FullPostBody.Available("A\u0000中\uD83D\uDE00\uD800")
        val expected = "99564fcb207181dade8792c6d1ec8fa3c65ca3120ddc5aa8a18db8dc2f2c829b"
        val digestChecks = AtomicInteger()
        val mainPublications = AtomicInteger()
        val parser: (FullPostBody, () -> Unit) -> PreparedFullPost = { input, active ->
            assertNotSame(Looper.getMainLooper(), Looper.myLooper())
            // Exercise the exact digest and cancellation callback on the helper's actual worker.
            // Source review additionally verifies the helper's own digest call shares this callback.
            val exactDigest = readerBodyDigest((input as FullPostBody.Available).html) {
                assertNotSame(Looper.getMainLooper(), Looper.myLooper())
                digestChecks.incrementAndGet()
                active()
            }
            assertEquals(expected, exactDigest)
            PreparedFullPost.Ready(listOf(FullPostPage("worker prepared", emptyList())))
        }
        compose.setContent {
            PreparedProbe(body, 0, parser) { state ->
                if (state.value != null) {
                    assertSame(Looper.getMainLooper(), Looper.myLooper())
                    mainPublications.incrementAndGet()
                }
            }
        }
        awaitText("worker prepared")
        awaitText("digest:$expected")
        assertTrue(digestChecks.get() >= 2)
        assertTrue(mainPublications.get() > 0)
    }

    @Test fun runtimeExceptionBecomesVisibleParsingErrorWithoutDigestAndRetryCanSucceed() {
        val attempt = mutableIntStateOf(0)
        val calls = AtomicInteger()
        val observed = AtomicReference<State<PreparedDetailReader?>?>()
        val body = FullPostBody.Available("RECOVERED_BODY")
        val parser: (FullPostBody, () -> Unit) -> PreparedFullPost = { input, active ->
            if (calls.incrementAndGet() == 1) throw IllegalStateException("Synthetic framework parse failure")
            prepareFullPost(input, active)
        }
        compose.setContent {
            MaterialTheme {
                Column {
                    PreparedProbe(body, attempt.intValue, parser) { observed.set(it) }
                    TextButton(onClick = { attempt.intValue++ }) { Text("retry preparation") }
                }
            }
        }
        awaitText(fullPostLimitMessage(FullPostBodyLimit.PARSING_ERROR))
        compose.onAllNodesWithText("pending").assertCountEquals(0)
        compose.onNodeWithText("digest:none").assertExists()
        assertEquals(PreparedFullPost.Unavailable(FullPostBodyLimit.PARSING_ERROR), observed.get()!!.value!!.prepared)
        assertNull(observed.get()!!.value!!.bodyDigest)
        compose.onNodeWithText("retry preparation").performClick()
        awaitText("RECOVERED_BODY")
        awaitText("digest:${readerBodyDigest(body.html)}")
        compose.onAllNodesWithText(fullPostLimitMessage(FullPostBodyLimit.PARSING_ERROR)).assertCountEquals(0)
        assertEquals(2, calls.get())
    }

    @Test fun actualBoundReaderRetryDelegatesToHelperAttemptAfterVisibleRuntimeError() {
        val body = FullPostBody.Available("BOUND_RETRY_RECOVERED_BODY")
        val attempt = mutableIntStateOf(0)
        val helperCalls = AtomicInteger()
        val retryCalls = AtomicInteger()
        val standaloneCalls = AtomicInteger()
        val published = AtomicReference<PreparedDetailReader?>()
        val helperParser: (FullPostBody, () -> Unit) -> PreparedFullPost = { input, active ->
            if (helperCalls.incrementAndGet() == 1) throw RuntimeException("Synthetic bound reader parse failure")
            prepareFullPost(input, active)
        }
        val standaloneParser: (FullPostBody, () -> Unit) -> PreparedFullPost = { _, _ ->
            standaloneCalls.incrementAndGet()
            PreparedFullPost.Ready(listOf(FullPostPage("WRONG_STANDALONE_RESULT", emptyList())))
        }
        compose.setContent {
            val prepared = rememberPreparedDetailReader(body, attempt.intValue, helperParser).value
            val page = remember(body) { mutableIntStateOf(0) }
            SideEffect { published.set(prepared) }
            MaterialTheme {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    val readyToBind = prepared
                    if (readyToBind == null) Text("preparing bound reader")
                    else CompositionLocalProvider(LocalDetailReaderBinding provides
                        DetailReaderBinding(body, readyToBind.prepared, page) {
                            retryCalls.incrementAndGet()
                            attempt.intValue++
                        }) {
                        FullPostText(body, "", {}, null, standaloneParser)
                    }
                }
            }
        }
        awaitText(fullPostLimitMessage(FullPostBodyLimit.PARSING_ERROR))
        compose.onNodeWithTag("full-post-limit").assertExists()
        compose.onNodeWithTag("full-post-progress").assertDoesNotExist()
        assertEquals(PreparedFullPost.Unavailable(FullPostBodyLimit.PARSING_ERROR), published.get()!!.prepared)
        assertNull(published.get()!!.bodyDigest)
        assertEquals(1, helperCalls.get())
        assertEquals(0, standaloneCalls.get())
        compose.onNodeWithText("重新排版正文").performScrollTo().performClick()
        awaitText("BOUND_RETRY_RECOVERED_BODY")
        compose.onNodeWithTag("full-post-page").assertTextEquals("BOUND_RETRY_RECOVERED_BODY")
        compose.onNodeWithTag("full-post-progress").assertDoesNotExist()
        compose.onNodeWithTag("full-post-pending").assertDoesNotExist()
        compose.onNodeWithTag("full-post-limit").assertDoesNotExist()
        compose.onAllNodesWithText("重新排版正文").assertCountEquals(0)
        compose.onAllNodesWithText("WRONG_STANDALONE_RESULT").assertCountEquals(0)
        compose.onAllNodesWithText("preparing bound reader").assertCountEquals(0)
        assertEquals(1, retryCalls.get())
        assertEquals(1, attempt.intValue)
        assertEquals(2, helperCalls.get())
        assertEquals(0, standaloneCalls.get())
        assertEquals(readerBodyDigest(body.html), published.get()!!.bodyDigest)
    }

    @Test fun unavailableOversizeAndUnsupportedReplacementPublishNoOldBodyOrDigest() {
        val body = mutableStateOf<FullPostBody>(FullPostBody.Available("previous ready body"))
        val parser: (FullPostBody, () -> Unit) -> PreparedFullPost = ::prepareFullPost
        val observed = AtomicReference<State<PreparedDetailReader?>?>()
        compose.setContent { PreparedProbe(body.value, 0, parser) { observed.set(it) } }
        awaitText("previous ready body")
        val replacements = listOf(
            FullPostBody.Available("x".repeat(FullPostBodyBounds.MAX_HTML_UNITS + 1)) to
                PreparedFullPost.Unavailable(FullPostBodyLimit.BODY_TOO_LARGE, FullPostBodyBounds.MAX_HTML_UNITS + 1),
            FullPostBody.Unavailable(FullPostBodyLimit.BODY_TOO_LARGE, FullPostBodyBounds.MAX_HTML_UNITS + 1) to
                PreparedFullPost.Unavailable(FullPostBodyLimit.BODY_TOO_LARGE, FullPostBodyBounds.MAX_HTML_UNITS + 1),
            FullPostBody.Unavailable(FullPostBodyLimit.BODY_TOO_LARGE, FullPostBodyBounds.MAX_HTML_UNITS + 2) to
                PreparedFullPost.Unavailable(FullPostBodyLimit.BODY_TOO_LARGE, FullPostBodyBounds.MAX_HTML_UNITS + 2),
            FullPostBody.Unavailable(FullPostBodyLimit.STRUCTURED_ARTICLE) to
                PreparedFullPost.Unavailable(FullPostBodyLimit.STRUCTURED_ARTICLE),
            FullPostBody.Available("text<img src='https://example.test/image'/>") to
                PreparedFullPost.Unavailable(FullPostBodyLimit.UNSUPPORTED_MARKUP),
        )
        replacements.forEach { (input, expected) ->
            compose.runOnIdle { body.value = input }
            awaitText(fullPostLimitMessage(expected.limit, expected.receivedUnits))
            compose.onNodeWithText("digest:none").assertExists()
            compose.onAllNodesWithText("previous ready body").assertCountEquals(0)
            compose.onAllNodesWithText("pending").assertCountEquals(0)
            assertEquals(expected, observed.get()!!.value!!.prepared)
            assertNull(observed.get()!!.value!!.bodyDigest)
        }
    }

    @Test fun cancelledBindingNeverPublishesParsingErrorAndNewAttemptCanPrepare() {
        val attempt = mutableIntStateOf(0)
        val calls = AtomicInteger()
        val threwCancellation = AtomicBoolean()
        val observed = AtomicReference<State<PreparedDetailReader?>?>()
        val cancellation = CancellationException("Synthetic reader cancellation")
        val parser: (FullPostBody, () -> Unit) -> PreparedFullPost = { input, active ->
            if (calls.incrementAndGet() == 1) {
                threwCancellation.set(true)
                throw cancellation
            }
            prepareFullPost(input, active)
        }
        compose.setContent {
            MaterialTheme {
                Column {
                    PreparedProbe(FullPostBody.Available("AFTER_CANCEL"), attempt.intValue, parser) { observed.set(it) }
                    TextButton(onClick = { attempt.intValue++ }) { Text("retry cancelled preparation") }
                }
            }
        }
        awaitSignal(threwCancellation)
        compose.waitForIdle()
        compose.onNodeWithText("pending").assertExists()
        compose.onNodeWithText("digest:none").assertExists()
        compose.onAllNodesWithText(fullPostLimitMessage(FullPostBodyLimit.PARSING_ERROR)).assertCountEquals(0)
        assertNull(observed.get()!!.value)
        compose.onNodeWithText("retry cancelled preparation").performClick()
        awaitText("AFTER_CANCEL")
        assertEquals(2, calls.get())
    }

    @Test fun preparationCancellationPropagatesUnchangedInsteadOfPublishingUnavailable() {
        val cancellation = CancellationException("Exact cancellation instance")
        val published = AtomicInteger()
        val outcome = mutableStateOf("not finished")
        compose.setContent {
            LaunchedEffect(Unit) {
                try {
                    parseAndPublishFullPost(FullPostBody.Available("body"), parser = { _, _ -> throw cancellation }) {
                        published.incrementAndGet()
                    }
                    fail("Cancellation must propagate")
                } catch (actual: CancellationException) {
                    assertEquals(cancellation.javaClass, actual.javaClass)
                    assertEquals(cancellation.message, actual.message)
                    assertTrue("Cancellation must retain its original cause, allowing stack-trace recovery copies",
                        generateSequence<Throwable>(actual) { it.cause }.take(8).any { it === cancellation })
                    outcome.value = "cancelled without publication"
                }
            }
            Text(outcome.value)
        }
        // Observe the Main continuation through semantics, not only an atomic worker flag.
        awaitText("cancelled without publication")
        assertEquals(0, published.get())
    }

    @Composable
    private fun PreparedProbe(body: FullPostBody, attempt: Int,
        parser: (FullPostBody, () -> Unit) -> PreparedFullPost,
        observe: (State<PreparedDetailReader?>) -> Unit = {}) {
        val state = rememberPreparedDetailReader(body, attempt, parser)
        val result = state.value
        SideEffect { observe(state) }
        Column {
            when (val prepared = result?.prepared) {
                null -> Text("pending")
                is PreparedFullPost.Ready -> Text(prepared.pages.joinToString("") { it.text })
                is PreparedFullPost.Article -> Text("structured article")
                is PreparedFullPost.Unavailable -> Text(fullPostLimitMessage(prepared.limit, prepared.receivedUnits))
            }
            Text("digest:${result?.bodyDigest ?: "none"}")
        }
    }

    private fun awaitText(text: String) {
        compose.waitUntil(15_000) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun awaitSignal(signal: AtomicBoolean) {
        // Drive and observe the Compose/Main side while checking worker entry/return signals.
        compose.waitUntil(15_000) { compose.onRoot().fetchSemanticsNode(); signal.get() }
    }
}
