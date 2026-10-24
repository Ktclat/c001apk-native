// SPDX-License-Identifier: AGPL-3.0-only
package io.github.ktclat.c001apk.nativeapp.ui

import io.github.ktclat.c001apk.nativeapp.domain.FullPostBodyBounds
import io.github.ktclat.c001apk.nativeapp.domain.ReplyOrder
import kotlinx.coroutines.CancellationException
import org.junit.Assert.*
import org.junit.Test

/** No body, response, parsed result, measured layout object, or process bookmark is retained. */
class ReaderPositionStoreTest {
    private val owner = ReaderOwner("account-a", 7L)
    private val digest = "a".repeat(64)
    private val changedDigest = "b".repeat(64)

    @Test fun capacityIsStrictlyThirteenAndReacquisitionMakesTheLatestFeedMostRecent() {
        assertEquals(13, MAX_READER_BOOKMARKS)
        val store = ReaderPositionStore()
        val first = store.register(identity("0"))
        for (id in 1..12) store.register(identity(id.toString()))
        assertEquals(13, store.size)
        assertSame(first, store.register(identity("0")))
        store.register(identity("13"))
        assertEquals((0..13).map { it.toString() }.toSet() - "1", store.feedIds())
        assertEquals(13, store.size)
        for (id in 14..99) {
            store.register(identity(id.toString()))
            assertEquals("The cap is strict after every acquisition", 13, store.size)
        }
        assertEquals((87..99).map { it.toString() }.toSet(), store.feedIds())
    }

    @Test fun capacityOutsideOneThroughThirteenIsRejectedAndOneIsSupported() {
        listOf(Int.MIN_VALUE, -1, 0, 14, Int.MAX_VALUE).forEach { invalid ->
            expectInvalid { ReaderPositionStore(invalid) }
        }
        val store = ReaderPositionStore(1)
        store.register(identity("first"))
        store.register(identity("second"))
        assertEquals(setOf("second"), store.feedIds())
        assertEquals(1, store.size)
    }

    @Test fun onlyCurrentAndHistoryFeedRoutesRetainTheirBookmarks() {
        val store = ReaderPositionStore()
        listOf("keep", "child", "gone", "42").forEach { store.register(identity(it)) }
        store.retainRoutes(owner, listOf("home", "feed:keep", "reply:42", "feed:child", "feed:keep"))
        assertEquals(setOf("keep", "child"), store.feedIds())
        store.retainRoutes(owner, listOf("home", "reply:42"))
        assertEquals(0, store.size)
    }

    @Test fun routesBeyondTheLastThirteenCannotRetainDroppedFeedEntries() {
        val store = ReaderPositionStore()
        store.register(identity("old"))
        store.register(identity("current"))
        val routes = listOf("feed:old") + List(12) { "reply:$it" } + "feed:current"
        store.retainRoutes(owner, routes)
        assertEquals(setOf("current"), store.feedIds())
    }

    @Test fun cachedRepliesAndOtherFeedsDoNotStandInForTheActualBodyCacheEntry() {
        val store = ReaderPositionStore()
        val evicted = store.register(identity("evicted"))
        evicted.save(ReaderPosition(page = 2, index = 5, offset = 81))
        val kept = store.register(identity("kept"))
        store.retainCachedFeeds(setOf("feed:kept", "ordered:evicted:RECENT_REPLY:false", "feed:other"))
        assertEquals(setOf("kept"), store.feedIds())
        assertSame(kept, store.register(identity("kept")))
        evicted.save(ReaderPosition(page = 3, index = 6, offset = 91))
        assertFalse(store.commit(evicted))
        assertEquals(setOf("kept"), store.feedIds())
        val refetched = store.register(identity("evicted"))
        assertNotSame(evicted, refetched)
        assertEquals(ReaderPosition(), refetched.position)
    }

    @Test fun accountAndSameAccountGenerationChangesNeverShareReaderPositions() {
        val store = ReaderPositionStore()
        val first = store.register(identity("same-feed"))
        first.save(ReaderPosition(page = 2, offset = 90, order = ReplyOrder.POPULAR, authorOnly = true))
        val otherAccount = ReaderOwner("account-b", owner.generation)
        assertNull(store.controls(otherAccount, "same-feed"))
        val second = store.register(identity("same-feed", expectedOwner = otherAccount))
        assertNotSame(first, second)
        assertEquals(ReaderPosition(), second.position)
        store.discard(owner, "same-feed")
        assertEquals(1, store.size)
        second.save(ReaderPosition(page = 3, offset = 80))
        val newGeneration = otherAccount.copy(generation = otherAccount.generation + 1)
        val third = store.register(identity("same-feed", expectedOwner = newGeneration))
        assertNotSame(second, third)
        assertEquals(ReaderPosition(), third.position)
        assertNull(store.controls(otherAccount, "same-feed"))
        second.save(ReaderPosition(page = 4, offset = 100))
        assertFalse(store.commit(second))
        assertEquals(ReaderPosition(), store.controls(newGeneration, "same-feed"))
        assertEquals(1, store.size)
    }

    @Test fun routeOwnerSelectionClearsOldOwnerAndGuestIsASeparateOwner() {
        val store = ReaderPositionStore()
        store.register(identity("42")).save(ReaderPosition(page = 2))
        val guest = ReaderOwner(null, owner.generation)
        store.retainRoutes(guest, listOf("feed:42"))
        assertEquals(0, store.size)
        assertNull(store.controls(owner, "42"))
        val bookmark = store.register(identity("42", expectedOwner = guest))
        assertEquals(ReaderPosition(), bookmark.position)
        store.retainRoutes(guest.copy(generation = guest.generation + 1), listOf("feed:42"))
        assertEquals(0, store.size)
    }

    @Test fun equalBodyDigestReusesLatestPositionButEqualBodiesInDifferentFeedsStayIndependent() {
        val store = ReaderPositionStore()
        val html = "same exact ordinary body"
        val first = store.register(identity("a", readerBodyDigest(html)))
        first.save(ReaderPosition(page = 2, index = 4, offset = 71, rowKey = "reply:feed_reply:r"))
        val second = store.register(identity("b", readerBodyDigest(String(html.toCharArray()))))
        assertNotSame(first, second)
        assertEquals(ReaderPosition(), second.position)
        second.save(ReaderPosition(page = 1, offset = 36))
        val returnedA = store.register(identity("a", readerBodyDigest(String(html.toCharArray()))))
        assertSame(first, returnedA)
        assertEquals(71, returnedA.position.offset)
        returnedA.save(returnedA.position.copy(offset = 93))
        assertSame(second, store.register(identity("b", readerBodyDigest(html))))
        assertEquals(36, second.position.offset)
        assertEquals(93, store.register(identity("a", readerBodyDigest(html))).position.offset)
        assertEquals(2, store.size)
    }

    @Test fun changedDigestReplacesTheEntryAndOldDisposalCannotOverwriteIt() {
        val store = ReaderPositionStore()
        val old = store.register(identity("42"))
        old.save(ReaderPosition(page = 2, offset = 85))
        val replacement = store.register(identity("42", changedDigest))
        assertNotSame(old, replacement)
        assertEquals(ReaderPosition(), replacement.position)
        replacement.save(ReaderPosition(page = 1, offset = 32))
        old.save(ReaderPosition(page = 3, offset = 999))
        assertFalse(store.commit(old))
        assertSame(replacement, store.register(identity("42", changedDigest)))
        assertEquals(32, replacement.position.offset)
        assertEquals(1, store.size)
    }

    @Test fun orphanDisposalCannotReinsertAfterRoutePruningDataPruningDiscardOrClear() {
        val removals: List<(ReaderPositionStore) -> Unit> = listOf(
            { it.retainRoutes(owner, listOf("home")) },
            { it.retainCachedFeeds(emptySet()) },
            { it.discard(owner, "42") },
            { it.clear() },
        )
        removals.forEach { remove ->
            val store = ReaderPositionStore()
            val orphan = store.register(identity("42"))
            orphan.save(ReaderPosition(page = 2, offset = 46))
            remove(store)
            assertEquals(0, store.size)
            orphan.save(ReaderPosition(page = 4, offset = 87))
            assertFalse(store.commit(orphan))
            store.retainRoutes(owner, listOf("feed:42"))
            assertFalse("Even reallowing a route cannot revive its invalidated bookmark", store.commit(orphan))
            assertEquals(0, store.size)
            assertNull(store.controls(owner, "42"))
            val fresh = store.register(identity("42"))
            assertNotSame(orphan, fresh)
            assertEquals(ReaderPosition(), fresh.position)
        }
    }

    @Test fun capacityEvictionAlsoLeavesAnOrphanThatCannotReinsertItself() {
        val store = ReaderPositionStore(1)
        val orphan = store.register(identity("old"))
        val current = store.register(identity("current"))
        orphan.save(ReaderPosition(page = 2, offset = 72))
        assertFalse(store.commit(orphan))
        assertEquals(setOf("current"), store.feedIds())
        assertSame(current, store.register(identity("current")))
        assertEquals(ReaderPosition(), store.register(identity("old")).position)
    }

    @Test fun invalidFeedAndDigestIdentitiesAreRejectedWithoutChangingExistingEntries() {
        val store = ReaderPositionStore()
        val valid = store.register(identity("valid"))
        listOf("", "x".repeat(MAX_READER_KEY_UNITS + 1), "9".repeat(MAX_READER_KEY_UNITS + 1)).forEach { badFeed ->
            assertNull(store.prepare(identity(badFeed)))
        }
        listOf("", "a".repeat(63), "a".repeat(65), "A".repeat(64), "g".repeat(64)).forEach { badDigest ->
            assertNull(store.prepare(identity("valid", badDigest)))
        }
        assertEquals(setOf("valid"), store.feedIds())
        assertSame(valid, store.register(identity("valid")))
        store.register(identity("x".repeat(MAX_READER_KEY_UNITS)))
        assertEquals(2, store.size)
    }

    @Test fun abandonedPreparationCannotEvictEntriesReplaceBodiesOrSelectAnotherOwner() {
        val store = ReaderPositionStore(1)
        val committed = store.register(identity("current"))
        val position = ReaderPosition(page = 2, offset = 73, order = ReplyOrder.POPULAR, authorOnly = true)
        committed.save(position)
        val revision = store.revision
        val abandonedFeed = requireNotNull(store.prepare(identity("uncommitted")))
        val abandonedBody = requireNotNull(store.prepare(identity("current", changedDigest)))
        val abandonedOwner = requireNotNull(store.prepare(identity("current", expectedOwner = ReaderOwner("another-account", 9L))))
        assertNotSame(committed, abandonedBody)
        assertNotSame(committed, abandonedOwner)
        abandonedFeed.save(ReaderPosition(page = 4, offset = 999))
        abandonedBody.save(ReaderPosition(page = 4, offset = 999))
        abandonedOwner.save(ReaderPosition(page = 4, offset = 999))
        assertEquals(setOf("current"), store.feedIds())
        assertEquals(1, store.size)
        assertEquals(position, store.controls(owner, "current"))
        assertSame(committed, store.prepare(identity("current")))
        assertFalse(store.commit(abandonedFeed))
        assertFalse(store.commit(abandonedOwner))
        assertEquals(position, committed.position)
        assertSame("Pure preparation, orphan saves and rejected commits cannot invalidate a committed frame", revision, store.revision)
    }

    @Test fun commitRequiresSelectedOwnerAndCurrentOrHistoryRoute() {
        val store = ReaderPositionStore()
        val beforeOwner = requireNotNull(store.prepare(identity("42")))
        assertEquals(0, store.size)
        assertFalse(store.commit(beforeOwner))
        store.retainRoutes(owner, listOf("home", "reply:42"))
        assertFalse(store.commit(beforeOwner))
        val withoutRoute = requireNotNull(store.prepare(identity("42")))
        assertFalse(store.commit(withoutRoute))
        store.retainRoutes(owner, listOf("home", "feed:42"))
        assertFalse("Selecting the route does not revive an earlier uncommitted candidate", store.commit(beforeOwner))
        assertFalse(store.commit(withoutRoute))
        val current = requireNotNull(store.prepare(identity("42")))
        assertTrue(store.commit(current))
        assertSame(current, store.prepare(identity("42")))
        store.retainRoutes(owner.copy(generation = owner.generation + 1), listOf("feed:42"))
        assertFalse(store.commit(current))
        assertEquals(0, store.size)
    }

    @Test fun staleUnattachedCandidateCannotOverwriteANewerCommittedBody() {
        val store = ReaderPositionStore()
        store.retainRoutes(owner, listOf("feed:42"))
        val oldCandidate = requireNotNull(store.prepare(identity("42")))
        oldCandidate.save(ReaderPosition(page = 3, offset = 81))
        val newCandidate = requireNotNull(store.prepare(identity("42", changedDigest)))
        newCandidate.save(ReaderPosition(page = 1, offset = 29))
        assertEquals(0, store.size)
        val beforeCommit = store.revision
        assertTrue(store.commit(newCandidate))
        assertNotSame(beforeCommit, store.revision)
        assertFalse(store.commit(oldCandidate))
        oldCandidate.save(ReaderPosition(page = 4, offset = 999))
        assertFalse(store.commit(oldCandidate))
        assertSame(newCandidate, store.prepare(identity("42", changedDigest)))
        assertEquals(ReaderPosition(page = 1, offset = 29), store.controls(owner, "42"))
        assertEquals(1, store.size)
    }

    @Test fun cacheUpdatesInvalidateUnattachedCandidatesEvenWhenNoAttachedEntryIsPruned() {
        val store = ReaderPositionStore()
        store.retainRoutes(owner, listOf("feed:42"))
        val beforeCacheUpdate = requireNotNull(store.prepare(identity("42")))
        val beforeRevision = store.revision
        store.retainCachedFeeds(setOf("feed:42"))
        assertNotSame(beforeRevision, store.revision)
        assertFalse(store.commit(beforeCacheUpdate))
        assertEquals(0, store.size)
        val committed = requireNotNull(store.prepare(identity("42")))
        assertTrue(store.commit(committed))
        committed.save(ReaderPosition(page = 2, offset = 73))
        val beforeEviction = requireNotNull(store.prepare(identity("42", changedDigest)))
        store.retainCachedFeeds(emptySet())
        assertFalse(store.commit(beforeEviction))
        assertFalse(store.commit(committed))
        assertEquals(0, store.size)
        // The still-retained route can acquire a fresh bookmark only after fresh preparation.
        store.retainCachedFeeds(setOf("feed:42"))
        val refetched = requireNotNull(store.prepare(identity("42", changedDigest)))
        assertTrue(store.commit(refetched))
        assertEquals(ReaderPosition(), refetched.position)
        assertEquals(1, store.size)
    }

    @Test fun committedRevisitUpdatesRecencyOnceButRepeatedCommitDoesNotInvalidateFrames() {
        val store = ReaderPositionStore()
        store.retainRoutes(owner, listOf("feed:first", "feed:second", "feed:pending"))
        val first = requireNotNull(store.prepare(identity("first")))
        assertTrue(store.commit(first))
        val second = requireNotNull(store.prepare(identity("second")))
        assertTrue(store.commit(second))
        val pending = requireNotNull(store.prepare(identity("pending")))
        val beforeRevisit = store.revision
        assertSame(first, store.prepare(identity("first")))
        assertSame(beforeRevisit, store.revision)
        assertTrue(store.commit(first))
        assertNotSame(beforeRevisit, store.revision)
        assertEquals(listOf("second", "first"), store.feedIds().toList())
        assertFalse(store.commit(pending))
        val afterRevisit = store.revision
        repeat(3) { assertTrue(store.commit(first)) }
        assertSame("Repeated committed SideEffects must not create a recomposition loop", afterRevisit, store.revision)
        assertSame(first, store.prepare(identity("first")))
        assertSame(second, store.prepare(identity("second")))
    }

    @Test fun savedPagesAndOffsetsAreNonnegativeAndKeysAreBoundedWithoutKeepingLargeValues() {
        val bookmark = ReaderBookmark(identity("42"))
        bookmark.save(ReaderPosition(page = Int.MIN_VALUE, index = Int.MIN_VALUE, offset = Int.MIN_VALUE))
        assertEquals(ReaderPosition(), bookmark.position)
        val maximumKey = "r".repeat(MAX_READER_KEY_UNITS)
        bookmark.save(ReaderPosition(page = Int.MAX_VALUE, index = Int.MAX_VALUE, offset = Int.MAX_VALUE,
            rowKey = maximumKey, replyKey = maximumKey, order = ReplyOrder.NEWEST, authorOnly = true))
        assertEquals(MAX_READER_PAGES - 1, bookmark.position.page)
        assertEquals(Int.MAX_VALUE, bookmark.position.index)
        assertEquals(Int.MAX_VALUE, bookmark.position.offset)
        assertEquals(maximumKey, bookmark.position.rowKey)
        assertEquals(maximumKey, bookmark.position.replyKey)
        assertEquals(ReplyOrder.NEWEST, bookmark.position.order)
        assertTrue(bookmark.position.authorOnly)
        bookmark.save(bookmark.position.copy(rowKey = maximumKey + "x", replyKey = maximumKey + "x"))
        assertNull(bookmark.position.rowKey)
        assertNull(bookmark.position.replyKey)
    }

    @Test fun fixedRowsRestoreByTheirStableKeysRatherThanAnOldIndex() {
        var lookups = 0
        val saved = ReaderPosition(page = 2, index = Int.MAX_VALUE, offset = 83, replyKey = "old-filter")
        val post = restoredReaderPosition(saved, 3, "new-filter") { lookups++; 99 }
        assertEquals(0, post.index)
        assertEquals(83, post.offset)
        val controls = restoredReaderPosition(saved.copy(rowKey = "comment-controls"), 3, "new-filter") { lookups++; 99 }
        assertEquals(1, controls.index)
        assertEquals(83, controls.offset)
        assertEquals(0, lookups)
    }

    @Test fun stableReplyKeyResolvesCurrentRowAndPreservesControlsPageAndPixelOffset() {
        val replyKey = "ordered:42:POPULAR:true"
        val rowKey = "reply:feed_reply:123"
        val saved = ReaderPosition(page = 2, index = 99, offset = 76, rowKey = rowKey,
            replyKey = replyKey, order = ReplyOrder.POPULAR, authorOnly = true)
        val lookedUp = mutableListOf<String>()
        val restored = restoredReaderPosition(saved, 3, replyKey) { key -> lookedUp += key; 5 }
        assertEquals(listOf(rowKey), lookedUp)
        assertEquals(saved.copy(index = 5), restored)
    }

    @Test fun missingRowsMissingReplyKeyAndChangedFiltersResetScrollButKeepVerifiedPage() {
        val replyKey = "ordered:42:RECENT_REPLY:false"
        val saved = ReaderPosition(page = 2, index = 9, offset = 86,
            rowKey = "reply:feed_reply:gone", replyKey = replyKey)
        val missingRow = restoredReaderPosition(saved, 3, replyKey) { null }
        assertEquals(2, missingRow.page)
        assertEquals(0, missingRow.index)
        assertEquals(0, missingRow.offset)
        listOf("ordered:42:NEWEST:false", "ordered:42:RECENT_REPLY:true", "ordered:other:RECENT_REPLY:false").forEach { changed ->
            var called = false
            val restored = restoredReaderPosition(saved, 3, changed) { called = true; 6 }
            assertFalse("A mismatched reply/filter hierarchy must not be looked up", called)
            assertEquals(saved.copy(index = 0, offset = 0), restored)
        }
        listOf(saved.copy(replyKey = null), saved.copy(rowKey = null)).forEach { unresolvable ->
            val restored = restoredReaderPosition(unresolvable, 3, replyKey) { fail("No stable key"); 6 }
            assertEquals(unresolvable.copy(index = 0, offset = 0), restored)
        }
    }

    @Test fun restorationClampsPagesToActualCountAndRejectsImpossiblePageCounts() {
        val tooHigh = restoredReaderPosition(ReaderPosition(page = Int.MAX_VALUE, index = Int.MAX_VALUE, offset = -1), 3, "replies") { null }
        assertEquals(2, tooHigh.page)
        assertEquals(0, tooHigh.index)
        assertEquals(0, tooHigh.offset)
        val tooLow = restoredReaderPosition(ReaderPosition(page = Int.MIN_VALUE), 1, "replies") { null }
        assertEquals(0, tooLow.page)
        assertEquals(MAX_READER_PAGES - 1,
            restoredReaderPosition(ReaderPosition(page = Int.MAX_VALUE), MAX_READER_PAGES, "replies") { null }.page)
        listOf(Int.MIN_VALUE, 0, MAX_READER_PAGES + 1, Int.MAX_VALUE).forEach { invalid ->
            expectInvalid { restoredReaderPosition(ReaderPosition(), invalid, "replies") { null } }
        }
    }

    @Test fun exactUtf16BigEndianDigestHasStableKnownVectorsWithoutNormalizationOrBom() {
        // SHA-256 of explicit UTF-16 code units, high byte first, without a BOM.
        val vectors = mapOf(
            "" to "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            "A" to "c00b4d3c929cb5cc316691ed4636f634576f2c9b2954767234c5274e9dde185d",
            "A\u0000中\uD83D\uDE00\uD800" to "99564fcb207181dade8792c6d1ec8fa3c65ca3120ddc5aa8a18db8dc2f2c829b",
            "\uD83D\uDE00" to "d4e887a680e68155b74f6002df62c2e8da129f928162f0e44ab3025f7653134f",
        )
        vectors.forEach { (html, expected) ->
            assertEquals(expected, readerBodyDigest(html))
            assertEquals(expected, readerBodyDigest(String(html.toCharArray())))
            assertTrue(readerBodyDigest(html).matches(Regex("[0-9a-f]{64}")))
        }
        assertNotEquals(readerBodyDigest("é"), readerBodyDigest("e\u0301"))
        assertNotEquals(readerBodyDigest("A"), readerBodyDigest("\uFEFFA"))
    }

    @Test fun unpairedSurrogatesRemainDistinctFromEachOtherReplacementAndQuestionMark() {
        val codeUnits = listOf("\uD800", "\uD801", "\uDC00", "\uFFFD", "?", "\uD83D\uDE00", "\uDE00\uD83D")
        val values = codeUnits.map { readerBodyDigest(it) }
        assertEquals(codeUnits.size, values.toSet().size)
        assertEquals("6e6535d29be7bfac2971dc0853620d739dd43a62c41409d21d39ccb9b29e224b", values[0])
        assertEquals("124e73fb267d800aaba112bcfbb5598e4f5737edc9a394e8f7dc3fb8d3f70267", values[1])
        assertEquals("34739425d55f591d570b36e6354822dbccd6453a78cbb9a61c05521248206762", values[2])
    }

    @Test fun exactMaximumDigestIsAcceptedAndOversizeRejectedBeforeAnyHashingCallback() {
        assertEquals(262_144, FullPostBodyBounds.MAX_HTML_UNITS)
        val maximum = "x".repeat(FullPostBodyBounds.MAX_HTML_UNITS)
        var checks = 0
        val result = readerBodyDigest(maximum) { checks++ }
        assertEquals(64, result.length)
        assertEquals(FullPostBodyBounds.MAX_HTML_UNITS / 1024 + 1, checks)
        assertEquals(result, readerBodyDigest(String(maximum.toCharArray())))
        var oversizeChecks = 0
        expectInvalid { readerBodyDigest(maximum + "x") { oversizeChecks++ } }
        assertEquals(0, oversizeChecks)
    }

    @Test fun digestCancellationPropagatesAtStartDuringScanAndFinalCheckIncludingEmptyBody() {
        listOf("" to 1, "abc" to 1, "abc" to 2, "x".repeat(4096) to 3).forEach { (html, cancelAt) ->
            val cancellation = CancellationException("cancel digest at $cancelAt")
            var checks = 0
            try {
                readerBodyDigest(html) { if (++checks == cancelAt) throw cancellation }
                fail("Digest cancellation must propagate")
            } catch (actual: CancellationException) {
                assertSame(cancellation, actual)
                assertEquals(cancelAt, checks)
            }
        }
    }

    /** Model a committed navigation followed by pure composition preparation and its SideEffect. */
    private fun ReaderPositionStore.register(identity: ReaderIdentity): ReaderBookmark {
        retainRoutes(identity.owner, feedIds().map { "feed:$it" } + "feed:${identity.feedId}")
        val bookmark = requireNotNull(prepare(identity))
        assertTrue(commit(bookmark))
        return bookmark
    }

    private fun identity(feed: String, bodyDigest: String = digest, expectedOwner: ReaderOwner = owner) =
        ReaderIdentity(expectedOwner, feed, bodyDigest)

    private fun expectInvalid(block: () -> Unit) {
        try { block(); fail("Expected IllegalArgumentException") }
        catch (_: IllegalArgumentException) { /* Invalid values are rejected, not silently retained. */ }
    }
}
