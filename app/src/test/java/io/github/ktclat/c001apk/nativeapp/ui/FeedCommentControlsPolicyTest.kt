// SPDX-License-Identifier: AGPL-3.0-only
package io.github.ktclat.c001apk.nativeapp.ui

import io.github.ktclat.c001apk.nativeapp.domain.*
import org.junit.Assert.*
import org.junit.Test

class FeedCommentControlsPolicyTest {
    private val missing = BrowseState(loaded=true,failure=ApiResult.Failure(FailureKind.BUSINESS,"unavailable"))
    @Test fun completedAbsentFailureDoesNotInventZeroCommentsOrOfferReply() {
        assertFalse(showFeedCommentControls(missing,false))
        assertFalse(showFeedCommentControls(missing.copy(failure=ApiResult.Failure(FailureKind.CAPTCHA,"verify")),false))
    }
    @Test fun loadingOrUnstartedDetailKeepsExistingBehavior() {
        assertTrue(showFeedCommentControls(missing.copy(loading=true),false))
        assertTrue(showFeedCommentControls(BrowseState(loading=true),false))
    }
    @Test fun cachedValidatedAndPublicBodiesKeepControlsDespiteLaterReadFailure() {
        for (public in listOf(false,true)) {
            val post = ContentItem("1","feed",message="retained",publicRead=public)
            assertTrue(showFeedCommentControls(missing.copy(items=listOf(post)),true))
        }
    }
    @Test fun validPostAndIndependentCommentFailureCannotHideControls() {
        assertTrue(showFeedCommentControls(BrowseState(loaded=true,items=listOf(ContentItem("1","feed"))),true))
    }
}
