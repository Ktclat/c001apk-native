// SPDX-License-Identifier: AGPL-3.0-only
package io.github.ktclat.c001apk.nativeapp.social

import org.junit.Assert.*
import org.junit.Test

class ComposerTextLimitTest {
    @Test fun asciiAndChineseHonorFiveHundredBoundary() {
        assertEquals(500, composerCharacterCount("中".repeat(500)))
        assertTrue(withinComposerTextLimit("a".repeat(500)))
        assertFalse(withinComposerTextLimit("中".repeat(501)))
    }
    @Test fun emojiZwjSkinToneFlagAndCombiningMarkCountAsWholeClusters() {
        listOf("👩🏽‍💻", "👨‍👩‍👧‍👦", "🇨🇳", "e\u0301").forEach {
            assertEquals(1, composerCharacterCount(it))
            assertTrue(withinComposerTextLimit(it.repeat(500)))
            assertFalse(withinComposerTextLimit(it.repeat(501)))
        }
    }
    @Test fun plainEmojiTokensUseSourceTextLengthRatherThanRenderedImageCount() {
        assertEquals(4, composerCharacterCount("[微笑]"))
    }
    @Test fun counterSaturatesWithoutScanningEntireLongInput() {
        assertEquals(501, composerCharacterCount("x".repeat(20_000)))
        assertFalse(withinComposerTextLimit("e" + "\u0301".repeat(MAX_COMPOSER_CODE_UNITS)))
    }
    @Test fun emptyAndNewlinesAreCountedWithoutSilentlyChangingDraft() {
        assertEquals(0, composerCharacterCount(""))
        assertEquals(3, composerCharacterCount("a\nb"))
    }
}
