// SPDX-License-Identifier: AGPL-3.0-only
package io.github.ktclat.c001apk.nativeapp.ui

import io.github.ktclat.c001apk.nativeapp.domain.*
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class EventDetailParsingTest {
    @Test fun htmlContentDoesNotTurnMarkdownLookingLiteralIntoFeedFormatting() {
        val prepared = prepareEventHtml(FullPostBody.Available("<p>**literal stars** [text](https://example.com/)</p><a href=\"https://example.com/actual\">真实链接</a>")) {} as PreparedFullPost.Ready
        val text = prepared.pages.joinToString("") { it.text }
        assertTrue(text.contains("**literal stars** [text](https://example.com/)"))
        assertEquals(listOf("https://example.com/actual"), prepared.pages.flatMap { it.links }.map { it.url })
    }
    @Test fun unsupportedInlineMediaIsExplicitNotSilentlyDropped() {
        val prepared = prepareEventHtml(FullPostBody.Available("<p>before</p><video src='https://example.com/video'>video</video><p>tail</p>")) {}
        assertEquals(PreparedFullPost.Unavailable(FullPostBodyLimit.UNSUPPORTED_MARKUP), prepared)
    }
}
