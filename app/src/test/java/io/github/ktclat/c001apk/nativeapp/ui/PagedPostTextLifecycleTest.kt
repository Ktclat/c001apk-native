package io.github.ktclat.c001apk.nativeapp.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Same HTML must complete independently in every visible lazy composition. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PagedPostTextLifecycleTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun repeatedBodiesCompleteAfterLazyDisposalAndReentry() {
        val html = "<b>Repeated body</b>"
        compose.setContent {
            MaterialTheme {
                LazyColumn(Modifier.height(300.dp).testTag("posts")) {
                    items((0 until 30).toList(), key = { it }) { id ->
                        Box(Modifier.height(100.dp).testTag("post:$id")) { PagedPostText(html) {} }
                    }
                }
            }
        }
        awaitRow(0, "Repeated body")
        awaitRow(1, "Repeated body")
        awaitRow(2, "Repeated body")
        compose.onNodeWithTag("posts").performScrollToIndex(24)
        awaitRow(24, "Repeated body")
        compose.onNodeWithTag("posts").performScrollToIndex(0)
        awaitRow(0, "Repeated body")
        awaitRow(1, "Repeated body")
        awaitRow(2, "Repeated body")
    }

    @Test fun replacementBodyCannotRetainPreviousParsedValue() {
        val html = mutableStateOf("<b>Old body</b>")
        compose.setContent { MaterialTheme { Box(Modifier.heightIn(min = 80.dp).testTag("post:0")) { PagedPostText(html.value) {} } } }
        awaitRow(0, "Old body")
        compose.runOnIdle { html.value = "<b>New body</b>" }
        awaitRow(0, "New body")
        compose.onAllNodesWithText("Old body").assertCountEquals(0)
    }

    private fun awaitRow(id: Int, expected: String) {
        val rowText = hasText(expected) and hasAnyAncestor(hasTestTag("post:$id"))
        compose.waitUntil(15_000) { compose.onAllNodes(rowText).fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(rowText).assertIsDisplayed()
        compose.onAllNodes(hasText("正在排版…") and hasAnyAncestor(hasTestTag("post:$id"))).assertCountEquals(0)
    }
}
