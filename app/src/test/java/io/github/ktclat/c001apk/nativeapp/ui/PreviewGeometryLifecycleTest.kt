// SPDX-License-Identifier: AGPL-3.0-only
package io.github.ktclat.c001apk.nativeapp.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Controlled background parsing, real renderer and measurements. No scroll repair or saved body. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35])
class PreviewGeometryLifecycleTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val release = CountDownLatch(1)
    private val entered = CountDownLatch(1)
    private val returned = CountDownLatch(1)
    private val hold = AtomicBoolean(false)
    private val store = PreviewGeometryStore()
    private val parser: (String) -> ParsedPost = { raw ->
        if (hold.get()) {
            entered.countDown()
            try { check(release.await(15,TimeUnit.SECONDS)); decodePostHtml(raw) } finally { returned.countDown() }
        } else decodePostHtml(raw)
    }
    @After fun cleanup() { release.countDown(); store.close() }
    @Test fun pendingKeepsWholeReadyBlockIncludingDisclosureAndLinks() = checkReturn("same", true)
    @Test fun changedWidthRejectsOldGeometry() = checkReturn("width", false)
    @Test fun changedFontScaleRejectsOldGeometry() = checkReturn("font", false)
    @Test fun changedBodyRejectsOldGeometry() = checkReturn("body", false)
    @Test fun changedDisclosureRejectsOldGeometry() = checkReturn("disclosure", false)
    @Test fun closedOwnerRejectsOldGeometry() = checkReturn("owner", false)
    private fun checkReturn(change: String, retain: Boolean) {
        try {
        val shown = mutableStateOf(true)
        val width = mutableStateOf(360f)
        val font = mutableStateOf(1f)
        val body = mutableStateOf("标志正文".repeat(60) + "<a href='/t/test'>入口</a>")
        val incomplete = mutableStateOf(true)
        compose.setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalPreviewGeometryStore provides store, LocalPreviewPostParser provides parser,
                    LocalDensity provides Density(LocalDensity.current.density,font.value)) {
                    if (shown.value) Box(Modifier.width(width.value.dp)) { PagedPostText(body.value,incomplete.value) {} }
                }
            }
        }
        compose.waitUntil(5_000) { compose.onAllNodesWithText("正在排版…").fetchSemanticsNodes().isEmpty() && store.size()>0 }
        val readyHeight = compose.onNodeWithTag("post-preview-block").fetchSemanticsNode().size.height
        compose.runOnIdle { shown.value=false }; compose.waitForIdle(); hold.set(true)
        compose.runOnIdle {
            when(change) {
                "width" -> width.value=240f
                "font" -> font.value=1.6f
                "body" -> body.value="替换正文"
                "disclosure" -> incomplete.value=false
                "owner" -> store.close()
            }
            shown.value=true
        }
        compose.waitUntil(5_000) { compose.onRoot().fetchSemanticsNode(); entered.count==0L }
        compose.onNodeWithText("正在排版…").assertExists()
        val pendingHeight=compose.onNodeWithTag("post-preview-block").fetchSemanticsNode().size.height
        if(retain) assertEquals(readyHeight,pendingHeight)
        else assertTrue("Mismatching layout/body/owner must not reuse previous height",pendingHeight<readyHeight)
        release.countDown()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("正在排版…").fetchSemanticsNodes().isEmpty() }
        } finally {
            release.countDown()
            // Release before synchronizing Main, including on failed assertions or gate-entry timeouts.
            compose.waitForIdle()
            if (entered.count == 0L) compose.waitUntil(5_000) { compose.onRoot().fetchSemanticsNode(); returned.count == 0L }
            compose.waitForIdle()
        }
    }
}
