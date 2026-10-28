// SPDX-License-Identifier: AGPL-3.0-only
// New runtime regression; does not diagnose the historical Robolectric idle cascade.
package io.github.ktclat.c001apk.nativeapp.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class EmulatorNotificationNavigationTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private lateinit var fixture: EmulatorReadOnlyFixture
    @After fun close() { if (::fixture.isInitialized) fixture.close() }
    @Test fun actualSystemDropdownOpens501Feed502AndBackRetainsUnsupportedRowPolicy() = recordPrimaryTestFailure("actualSystemDropdownOpens501Feed502AndBackRetainsUnsupportedRowPolicy") {
        fixture = EmulatorReadOnlyFixture(compose, "notification") { request -> when(request.url.encodedPath) {
            "/v6/notification/atMeList" -> """{"data":[{"id":"101","entityType":"feed","uid":"7","username":"合成提及作者","message":"合成提及动态正文"}]}"""
            "/v6/notification/list" -> """{"data":[{"id":"501","entityType":"notification","fromuid":"99","fromusername":"合成系统通知","note":"<a href='/feed/502'>系统相关动态</a>"},{"id":"503","entityType":"notification","note":"<a href='https://example.invalid/'>外部系统消息</a>"},{"entityType":"card","entityTemplate":"noMoreDataCard"}]}"""
            "/v6/feed/detail" -> if (request.url.queryParameter("id") == "502") """{"data":{"id":"502","entityType":"feed","uid":"7","username":"合成详情作者","message":"合成详情完整正文","replynum":0,"likenum":0}}""" else null
            "/v6/feed/replyList" -> if (request.url.queryParameter("id") == "502") """{"data":[]}""" else null
            else -> null
        } }
        fixture.render(initialRoute = "notifications:MENTIONS")
        fixture.awaitText("合成提及动态正文"); fixture.capture("mentions")
        compose.onNodeWithText("@我的动态").performClick()
        compose.onNodeWithText("系统通知").assertIsDisplayed().performClick()
        fixture.awaitTag("notice-open:notification:501:0")
        val target = compose.onNodeWithTag("notice-open:notification:501:0").assertIsDisplayed()
        val before = target.getUnclippedBoundsInRoot()
        val scrollBefore = compose.onNodeWithTag("notification-list").fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()
        fixture.capture("system-before")
        target.performClick(); fixture.awaitText("合成详情完整正文"); fixture.awaitReplies("502")
        fixture.capture("feed502"); fixture.back(); fixture.awaitTag("notification-list")
        // No corrective scroll between Back and these current UI comparisons.
        compose.onNodeWithText("系统通知").assertIsDisplayed()
        val returned = compose.onNodeWithTag("notice-open:notification:501:0").assertIsDisplayed().getUnclippedBoundsInRoot()
        assertEquals(before, returned)
        val scrollAfter = compose.onNodeWithTag("notification-list").fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()
        assertEquals(scrollBefore, scrollAfter, .001f)
        EmulatorEvidence.text("notification-retained.txt", "before=$before\nafter=$returned\nscrollBefore=$scrollBefore\nscrollAfter=$scrollAfter\nNo corrective scroll before comparison")
        fixture.capture("back-retained")
        compose.onNodeWithTag("notification-list").performScrollToNode(hasTestTag("notice-unavailable:notification:503:1"))
        compose.onNodeWithTag("notice-row:notification:503:1").assertHasNoClickAction()
        val unavailable = compose.onNodeWithTag("notice-unavailable:notification:503:1").assertIsDisplayed().assertHasNoClickAction()
        val calls = fixture.requests.size
        // Visible unavailable text only, not nearby delete controls; view-level, not OS hit-test claim.
        unavailable.performTouchInput { click(center) }
        compose.waitForIdle(); assertEquals(calls, fixture.requests.size)
        fixture.capture("unsupported-stays-native")
        assertEquals(1, fixture.count("/v6/notification/atMeList")); assertEquals(1, fixture.count("/v6/notification/list"))
        assertEquals(1, fixture.count("/v6/feed/detail", "502")); assertEquals(1, fixture.count("/v6/feed/replyList", "502"))
        assertEquals(4, fixture.requests.size)
    }
}
