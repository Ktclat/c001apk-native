// SPDX-License-Identifier: AGPL-3.0-only
package io.github.ktclat.c001apk.nativeapp.feature.media

import org.junit.Assert.*
import org.junit.Test

class PublicImageRequestStateTest {
    private val failure = ImageLoadFailure(ImageLoadFailureKind.HTTP, 567)
    @Test fun failureOnlyExplicitRetryIsBoundedAndNeverPretendsSuccess() {
        val state = PublicImageRequestState()
        state.retry()
        assertEquals(0, state.attempt)
        state.finish(0, failure)
        assertEquals(MediaImagePhase.Error, state.phase)
        assertTrue(state.canRetry)
        state.retry(); state.retry()
        assertEquals(1, state.attempt)
        assertEquals(MediaImagePhase.Loading, state.phase)
        state.finish(0); state.finish(0, failure)
        assertEquals(MediaImagePhase.Loading, state.phase)
        state.finish(1, failure); state.retry()
        assertEquals(MediaImagePhase.Error, state.phase)
        assertFalse(state.canRetry)
        assertEquals(1, state.attempt)
    }
    @Test fun disposedUrlOrLoaderOwnerCannotFinishOrRetryAndNewOwnerStartsFresh() {
        val old = PublicImageRequestState(); old.dispose(); old.finish(0)
        assertEquals(MediaImagePhase.Loading, old.phase)
        val failed = PublicImageRequestState(); failed.finish(0, failure); failed.dispose(); failed.retry()
        assertEquals(0, failed.attempt)
        val new = PublicImageRequestState(); new.finish(0)
        assertEquals(MediaImagePhase.Success, new.phase)
        new.finish(0, failure)
        assertEquals(MediaImagePhase.Success, new.phase)
    }
    @Test fun largeFontNeedsSpaceAndSmallIconsNeverQualify() {
        assertFalse(publicImageRetryFits(48f, 48f, 1f))
        assertTrue(publicImageRetryFits(266f, 120f, 1f))
        assertFalse(publicImageRetryFits(266f, 120f, 2.4f))
        assertTrue(publicImageRetryFits(266f, 220f, 2.4f))
        assertFalse(publicImageRetryFits(150f, 220f, 2.4f))
    }
}
