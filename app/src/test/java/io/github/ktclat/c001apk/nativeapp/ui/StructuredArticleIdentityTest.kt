// SPDX-License-Identifier: AGPL-3.0-only
package io.github.ktclat.c001apk.nativeapp.ui

import io.github.ktclat.c001apk.nativeapp.domain.*
import kotlinx.coroutines.CancellationException
import org.junit.Assert.*
import org.junit.Test

class StructuredArticleIdentityTest {
    private fun body(raw: String, title: String? = null, cover: String? = null) =
        FullPostBody.Structured(raw, title, cover, listOf(ArticleBlock.Text("display value is not the identity")))

    @Test fun exactUtf16DomainAndSignedLengthFramingHaveIndependentGoldenVectors() {
        // Independently calculated from ASCII domain + signed Int32BE lengths + UTF16BE code units.
        // Deliberate unpaired surrogate ensures UTF8 conversion/replacement cannot silently substitute.
        assertEquals("0fbb8ee4ef4eb4e7e186e56078a1055098b743321aeaa7309a2d701807634c29", readerArticleDigest(body("A\u0000中\uD83D\uDE00\uD800", "title", "https://image.coolapk.com/cover.jpg")))
        assertEquals("c944b4c298f6d2597c4cb12619fcff8a6235ebb8318078c265e206beecdd0f22", readerArticleDigest(body("[]")))
        assertEquals("656982aeff086dac099f0ca5f07477f0a41a8a35527145fdcc0396f4c3c9ba9f", readerArticleDigest(body("[]", "", "")))
    }

    @Test fun domainSeparationAndLengthFramingPreventConcatenationOrOrdinaryCollisions() {
        assertNotEquals(readerBodyDigest("[]"), readerArticleDigest(body("[]")))
        assertNotEquals(readerArticleDigest(body("ab", "c")), readerArticleDigest(body("a", "bc")))
        assertNotEquals(readerArticleDigest(body("ab", "c", "d")), readerArticleDigest(body("a", "b", "cd")))
        val optional = listOf(body("[]"), body("[]", ""), body("[]", cover = ""), body("[]", "", ""))
        assertEquals(optional.size, optional.map { readerArticleDigest(it) }.toSet().size)
    }

    @Test fun titleCoverCaptionUrlTypeOrderAndEvenNonRenderedSourceChangesInvalidateIdentity() {
        val raw = """[{"type":"text","message":"HEAD"},{"type":"image","url":"https://image.coolapk.com/a.jpg","description":"CAPTION"},{"type":"shareUrl","url":"/feed/43","title":"LABEL","subTitle":"IGNORED"}]"""
        val original = body(raw, "TITLE", "https://image.coolapk.com/cover.jpg")
        val replacements = listOf(
            original.copy(title = "CHANGED_TITLE"), original.copy(cover = "https://image.coolapk.com/changed.jpg"),
            original.copy(rawOutput = raw.replace("CAPTION", "NEW_CAPTION")),
            original.copy(rawOutput = raw.replace("a.jpg", "b.jpg")),
            original.copy(rawOutput = raw.replace("/feed/43", "/feed/44")),
            original.copy(rawOutput = raw.replace("LABEL", "NEW_LABEL")),
            original.copy(rawOutput = raw.replace("\"type\":\"text\"", "\"type\":\"shareUrl\"")),
            original.copy(rawOutput = raw.replace("HEAD", "NEW_HEAD")),
            original.copy(rawOutput = """[{"type":"shareUrl","url":"/feed/43","title":"LABEL","subTitle":"IGNORED"},{"type":"image","url":"https://image.coolapk.com/a.jpg","description":"CAPTION"},{"type":"text","message":"HEAD"}]"""),
            original.copy(rawOutput = raw.replace("IGNORED", "CHANGED_UNUSED_SUBTITLE")),
            original.copy(rawOutput = " " + raw),
        )
        val digest = readerArticleDigest(original)
        for (replacement in replacements) assertNotEquals(digest, readerArticleDigest(replacement))
        assertEquals(replacements.size, replacements.map { readerArticleDigest(it) }.toSet().size)
    }

    @Test fun equalImmutableReplacementPreservesIdentityAcrossDistinctStringInstances() {
        val original = body("""[{"type":"text","message":"same"}]""", "title", "cover")
        val replacement = original.copy(rawOutput = String(original.rawOutput.toCharArray()),
            title = String(requireNotNull(original.title).toCharArray()), cover = String(requireNotNull(original.cover).toCharArray()),
            blocks = original.blocks.toList())
        assertNotSame(original, replacement); assertEquals(original, replacement)
        assertEquals(readerArticleDigest(original), readerArticleDigest(replacement))
    }

    @Test fun exactUnpairedSurrogatesAreNotReplacementCharactersOrOtherCodeUnits() {
        val values = listOf("\uD800", "\uD801", "\uDC00", "\uFFFD", "\uD83D\uDE00", "\uD83D\uDE01")
        assertEquals(values.size, values.map { readerArticleDigest(body(it)) }.toSet().size)
    }

    @Test fun digestCancellationChecksTheEntireSourceAndPropagatesTheExactCancellation() {
        val article = body("x".repeat(200_000), "title", "cover")
        var total = 0
        readerArticleDigest(article) { total++ }
        assertTrue(total > 100)
        for (stopAt in listOf(1, total / 2, total)) {
            var checks = 0
            val cancellation = CancellationException("synthetic digest cancellation")
            try {
                readerArticleDigest(article) { if (++checks == stopAt) throw cancellation }
                fail("Digest must not succeed after cancellation")
            } catch (actual: CancellationException) { assertSame(cancellation, actual) }
        }
    }

    @Test fun bodyTypeReplacementInvalidatesOldBookmarksWithoutChangingOwnerRules() {
        val owner = ReaderOwner("synthetic-account", 7)
        val store = ReaderPositionStore()
        store.retainRoutes(owner, listOf("feed:42"))
        val article = body("""[{"type":"text","message":"same visible text"}]""")
        val firstIdentity = ReaderIdentity(owner, "42", readerArticleDigest(article))
        val first = requireNotNull(store.prepare(firstIdentity)); assertTrue(store.commit(first))
        first.save(ReaderPosition(page = 2, offset = 73))
        assertSame(first, store.prepare(firstIdentity.copy(bodyDigest = readerArticleDigest(article.copy()))))
        val ordinaryIdentity = firstIdentity.copy(bodyDigest = readerBodyDigest("same visible text"))
        val ordinary = requireNotNull(store.prepare(ordinaryIdentity)); assertTrue(store.commit(ordinary))
        assertEquals(ReaderPosition(), ordinary.position)
        assertFalse(store.commit(first))
        val returned = requireNotNull(store.prepare(firstIdentity)); assertTrue(store.commit(returned))
        assertNotSame(first, returned); assertEquals(ReaderPosition(), returned.position)
        assertEquals(1, store.size)
        store.retainRoutes(owner.copy(generation = 8), listOf("feed:42"))
        assertFalse(store.commit(returned)); assertEquals(0, store.size)
    }
    @Test fun boundedArticlePagesAboveOrdinaryMaximumRestoreWithoutBroadeningOrdinaryValidation() {
        assertEquals(200, MAX_READER_PAGES)
        assertEquals(66, MAX_ORDINARY_READER_PAGES)
        val article = PreparedFullPost.Article(listOf(ArticlePage(emptyList(), 0)), emptyList())
        val ordinary = PreparedFullPost.Ready(listOf(FullPostPage("ordinary", emptyList())))
        assertEquals(200, article.readerPageLimit()); assertEquals(66, ordinary.readerPageLimit())
        val saved = ReaderPosition(page = 199, index = 0, offset = 79)
        assertEquals(saved, restoredReaderPosition(saved, 200, "replies", article.readerPageLimit()) { null })
        assertEquals(0, restoredReaderPosition(saved, 1, "replies", ordinary.readerPageLimit()) { null }.page)
        assertEquals(65, restoredReaderPosition(saved, 66, "replies", ordinary.readerPageLimit()) { null }.page)
        for ((count, maximum) in listOf(201 to 200, 67 to 66, 0 to 200, 1 to 201)) {
            try { restoredReaderPosition(saved, count, "replies", maximum) { null }; fail("Invalid kind-specific page count must reject") }
            catch (_: IllegalArgumentException) { }
        }
    }

    @Test fun highArticlePageBookmarkRetainsStableReplyAnchorButCannotReviveAfterPruning() {
        val owner = ReaderOwner("synthetic-account", 7)
        val store = ReaderPositionStore(); store.retainRoutes(owner, listOf("feed:42"))
        val identity = ReaderIdentity(owner, "42", readerArticleDigest(body("source")))
        val bookmark = requireNotNull(store.prepare(identity)); assertTrue(store.commit(bookmark))
        bookmark.save(ReaderPosition(page = 199, index = 99, offset = 37, rowKey = "reply:feed_reply:r", replyKey = "replies"))
        assertEquals(199, bookmark.position.page)
        val restored = restoredReaderPosition(bookmark.position, 200, "replies", 200) { if (it == "reply:feed_reply:r") 5 else null }
        assertEquals(5, restored.index); assertEquals(37, restored.offset); assertEquals(199, restored.page)
        val wrongReplies = restoredReaderPosition(bookmark.position, 200, "different-replies", 200) { 5 }
        assertEquals(0, wrongReplies.index); assertEquals(0, wrongReplies.offset)
        store.retainCachedFeeds(emptySet()); assertFalse(store.commit(bookmark)); assertEquals(0, store.size)
        val fresh = requireNotNull(store.prepare(identity)); assertTrue(store.commit(fresh))
        assertEquals(ReaderPosition(), fresh.position)
    }

}
