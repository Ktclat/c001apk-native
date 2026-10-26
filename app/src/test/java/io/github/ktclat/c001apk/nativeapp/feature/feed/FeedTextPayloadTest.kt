// SPDX-License-Identifier: AGPL-3.0-only
package io.github.ktclat.c001apk.nativeapp.feature.feed

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class FeedTextPayloadTest {
    @Test fun copyIsOnlyMirrorLinkAndShareUsesPlainTitleThenBothLinks() {
        val result = feedTextPayload("123", "<b>标题</b> &amp;\n  合成测试", "不会分享这段正文")!!
        assertEquals("https://www.coolapk1s.com/feed/123", result.copyLink)
        assertEquals("标题 & 合成测试\nhttps://www.coolapk1s.com/feed/123\nhttps://www.coolapk.com/feed/123\n分享自c001apk", result.shareText)
    }
    @Test fun ordinaryFeedFallsBackToPlainMessageThenGenericLabel() {
        assertEquals("普通 动态 正文", feedTextPayload("1", "<b></b>", "普通<br>动态\t正文")!!.shareText.lineSequence().first())
        assertEquals("动态", feedTextPayload("1", "", "<br> &nbsp; ")!!.shareText.lineSequence().first())
    }
    @Test fun flutterParagraphBrEntityAndImageTextRulesRemainPlain() {
        assertEquals("甲 乙 丙丁 & 中", summary("<p>甲<br>乙<br/>丙</p><p>丁 &amp; &#20013;</p>"))
        assertEquals("甲 乙丙", summary("甲\n乙<img src='data:x' alt='不追加 alt'>丙"))
        assertEquals("可见", summary("<a href='https://example.invalid/' title='<div>opaque</div>'>可见</a>"))
        assertEquals("甲乙", summary("<div title='>synthetic-private'>甲</div><p>乙</p>"))
    }
    @Test fun hrefImageMetadataAndOpaqueQueryAreNeverShared() {
        val result = feedTextPayload("12", "", "<a href='https://example.invalid/?token=synthetic-secret'>可见文字</a><img src='https://example.invalid/private.png' alt='private-metadata'>")!!
        assertEquals("可见文字", result.shareText.lineSequence().first())
        assertFalse(result.shareText.contains("synthetic-secret"))
        assertFalse(result.shareText.contains("private"))
    }
    @Test fun strictFeedIdsDoNotAcceptUrlWhitespacePathQueryOrUnicodeDigits() {
        listOf("", " ", "12 ", " 12", "1/2", "../1", "1?token=opaque", "1#fragment", "1%2F2", "https://www.coolapk.com/feed/1", "１", "١", "1\n", "1\\2", "1".repeat(4097)).forEach {
            assertNull(it, feedCopyLink(it)); assertNull(it, feedTextPayload(it, "", ""))
        }
        assertEquals("https://www.coolapk1s.com/feed/00123", feedCopyLink("00123"))
        assertNotNull(feedCopyLink("1".repeat(4096)))
    }
    @Test fun hundredCharactersStayWholeAndLongerTextGetsOneEllipsis() {
        assertEquals("文".repeat(100), summary("文".repeat(100)))
        assertEquals("文".repeat(100) + "…", summary("文".repeat(3000)))
    }
    @Test fun emojiZwjCombiningMarksAndRegionalFlagsStayWhole() {
        listOf("👩🏽‍💻", "👨‍👩‍👧‍👦", "e\u0301", "🇨🇳").forEach { cluster ->
            assertEquals(cluster.repeat(100) + "…", summary(cluster.repeat(101)))
        }
    }
    @Test @Config(sdk = [26]) fun minimumApiDoesNotSplitKnownZwjCombiningOrFlags() {
        listOf("👨‍👩‍👧‍👦", "e\u0301", "🇨🇳").forEach { cluster ->
            assertEquals(cluster.repeat(100) + "…", summary(cluster.repeat(101)))
        }
    }
    @Test fun hugeUnexpectedInputFailsBeforeHtmlParsingRatherThanTruncatingMarkup() {
        assertThrows(FeedShareContentTooLarge::class.java) { summary("a".repeat(262_145)) }
        assertThrows(FeedShareContentTooLarge::class.java) { summary("a" + "\u0301".repeat(17_000)) }
    }
    @Test fun longQuotedAttributeIsBoundedAndDoesNotLeakIntoSummary() {
        assertEquals("可见", summary("<div title='" + ">opaque".repeat(20_000) + "'>可见</div>"))
        assertThrows(FeedShareContentTooLarge::class.java) { summary("<b>".repeat(8_193)) }
    }
    @Test fun hugeUnusedBodyIsNotExaminedWhenTitleExists() {
        assertEquals("标题", feedTextPayload("1", "标题", "a".repeat(262_145))!!.shareText.lineSequence().first())
    }
    private fun summary(text: String) = feedTextPayload("1", "", text)!!.shareText.lineSequence().first()
}
