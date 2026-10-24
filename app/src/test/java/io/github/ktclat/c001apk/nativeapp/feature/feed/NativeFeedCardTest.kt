package io.github.ktclat.c001apk.nativeapp.feature.feed

import io.github.ktclat.c001apk.nativeapp.domain.FeedReferenceKind
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Offline fixtures only. No account repository or network mutation is reachable. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w393dp-h852dp-420dpi")
class NativeFeedCardTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private fun fixture(state: FeedLikeState = FeedLikeState.UNKNOWN) = FeedCardModel(
        id = "synthetic", author = "合成用户", authorId = "fixture", title = "原始标题", body = "正文不是重复标题",
        device = "合成设备", info = "数码体验", time = "2分钟前", pinned = true, replies = 12, likes = 24, likeState = state,
        forward = FeedReference("forward-fixture", "转发作者", body = "嵌套转发"),
        hotReply = FeedReference("reply-fixture", "热评作者", body = "嵌套热评", kind = FeedReferenceKind.HOT_REPLY, parentFeedId = "synthetic"),
        relations = listOf(FeedRelation("topic-fixture", "合成话题", null)),
    )
    @Test fun separateActionsDoNotAlsoOpenPostAndFavoriteLivesInMenu() {
        var opened = 0; var comments = 0; var favorites = 0; var author = 0
        compose.setContent { MaterialTheme { NativeFeedCard(fixture(), FeedCardActions(
            openPost = { opened++ }, openAuthor = { author++ }, openComments = { comments++ }, openLink = {}, openReference = {}, favorite = { favorites++ })) } }
        compose.onNodeWithTag("feed-comments:synthetic").performClick()
        assertEquals(1, comments); assertEquals(0, opened)
        compose.onNodeWithText("收藏到本机").assertDoesNotExist()
        compose.onNodeWithTag("feed-more:synthetic").performClick()
        compose.onNodeWithText("收藏到本机").performClick()
        assertEquals(1, favorites); assertEquals(0, opened)
        compose.onNodeWithTag("feed-like:synthetic").assertIsNotEnabled()
    }
    @Test fun loginRequiredDoesNotCallToggle() {
        var login = 0; var toggles = 0
        compose.setContent { MaterialTheme { NativeFeedCard(fixture(FeedLikeState.LOGIN_REQUIRED), FeedCardActions(
            openPost = {}, openAuthor = null, openComments = {}, openLink = {}, openReference = {}, login = { login++ }, toggleLike = { toggles++ })) } }
        compose.onNodeWithTag("feed-like:synthetic").performClick()
        assertEquals(1, login); assertEquals(0, toggles)
    }
    @Test fun explicitLikedStateCanToggle() {
        var toggles = 0
        compose.setContent { MaterialTheme { NativeFeedCard(fixture(FeedLikeState.LIKED), FeedCardActions(
            openPost = {}, openAuthor = null, openComments = {}, openLink = {}, openReference = {}, toggleLike = { toggles++ })) } }
        compose.onNodeWithTag("feed-like:synthetic").performClick()
        assertEquals(1, toggles)
        // Each reference parses independently on Dispatchers.Default; hot-reply readiness
        // does not imply that the forwarded body has also been published to semantics.
        compose.waitUntil(10_000) {
            compose.onAllNodesWithText("@转发作者：嵌套转发", substring = true).fetchSemanticsNodes().isNotEmpty() &&
                compose.onAllNodesWithText("热评作者：嵌套热评", substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("@转发作者：嵌套转发", substring = true).assertExists()
        compose.onNodeWithText("热评作者：嵌套热评", substring = true).assertExists()
    }
}
