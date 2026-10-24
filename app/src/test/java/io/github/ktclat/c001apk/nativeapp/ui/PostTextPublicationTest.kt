package io.github.ktclat.c001apk.nativeapp.ui

import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
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
class PostTextPublicationTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun actualHtmlParserRunsOffMainAndPublicationRunsOnMain() {
        val parserCalled = AtomicBoolean()
        val published = mutableStateOf<String?>(null)
        compose.setContent {
            LaunchedEffect(Unit) {
                parseAndPublishPost("<b>Rendered body</b>", false, parser = { html ->
                    assertNotSame("Html parsing must not run on UI Looper", Looper.getMainLooper(), Looper.myLooper())
                    parserCalled.set(true)
                    decodePostHtml(html)
                }) { result ->
                    assertSame("State publication must use UI Looper", Looper.getMainLooper(), Looper.myLooper())
                    published.value = result.text
                }
            }
            Text(published.value ?: "pending")
        }
        compose.waitUntil(15_000) { compose.onAllNodesWithText("Rendered body").fetchSemanticsNodes().size == 1 }
        assertTrue(parserCalled.get())
    }

    @Test fun disposedCompositionCannotPublishAfterNonCooperativeParserReturns() {
        val show = mutableStateOf(true)
        val entered = AtomicBoolean()
        val completed = AtomicBoolean()
        val publications = AtomicInteger()
        val release = CountDownLatch(1)
        compose.setContent {
            if (show.value) {
                LaunchedEffect(Unit) {
                    try {
                        parseAndPublishPost("<b>Cancelled body</b>", false, parser = { html ->
                            assertNotSame(Looper.getMainLooper(), Looper.myLooper())
                            entered.set(true)
                            check(release.await(20, TimeUnit.SECONDS)) { "Test did not release parser" }
                            decodePostHtml(html)
                        }) { publications.incrementAndGet() }
                    } finally { completed.set(true) }
                }
                Text("mounted")
            } else Text("removed")
        }
        try {
            compose.waitUntil(15_000) { entered.get() }
            compose.runOnIdle { show.value = false }
            compose.onNodeWithText("removed").assertExists()
            release.countDown()
            compose.waitUntil(15_000) { completed.get() }
            assertEquals("Cancelled composition must not publish", 0, publications.get())
        } finally { release.countDown() }
    }

    @Test fun inputChangeImmediatelyClearsOldTextAndCancelledReplacementCannotOverwriteLatest() {
        val html = mutableStateOf("<b>Old body</b>")
        val entered = AtomicBoolean()
        val returned = AtomicBoolean()
        val release = CountDownLatch(1)
        val parser: (String) -> ParsedPost = { source ->
            assertNotSame(Looper.getMainLooper(), Looper.myLooper())
            if (source == "<b>Blocked replacement</b>") {
                entered.set(true)
                check(release.await(20, TimeUnit.SECONDS)) { "Test did not release replacement" }
            }
            decodePostHtml(source).also {
                if (source == "<b>Blocked replacement</b>") returned.set(true)
            }
        }
        compose.setContent {
            val parsed by rememberParsedPost(html.value, false, parser)
            Text(parsed?.text ?: "pending")
        }
        try {
            awaitText("Old body")
            compose.runOnIdle { html.value = "<b>Blocked replacement</b>" }
            // Synchronize the changed input frame before observing a background-only signal.
            compose.onNodeWithText("pending").assertExists()
            compose.onAllNodesWithText("Old body").assertCountEquals(0)
            compose.waitUntil(15_000) { entered.get() }
            compose.runOnIdle { html.value = "<b>Latest body</b>" }
            awaitText("Latest body")
            release.countDown()
            compose.waitUntil(15_000) { returned.get() }
            compose.waitForIdle()
            compose.onNodeWithText("Latest body").assertExists()
            compose.onAllNodesWithText("Blocked replacement").assertCountEquals(0)
            compose.onAllNodesWithText("Old body").assertCountEquals(0)
        } finally { release.countDown() }
    }

    private fun awaitText(text: String) {
        compose.waitUntil(15_000) { compose.onAllNodesWithText(text).fetchSemanticsNodes().size == 1 }
    }
}
