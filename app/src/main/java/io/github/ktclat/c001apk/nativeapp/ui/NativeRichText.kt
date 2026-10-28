// SPDX-License-Identifier: AGPL-3.0-only
// Native adaptation of Flutter html_text.dart; original assets, not Unicode approximations.
package io.github.ktclat.c001apk.nativeapp.ui

import android.graphics.Typeface
import android.text.Spanned
import android.text.style.TypefaceSpan
import android.text.style.StyleSpan
import android.text.style.StrikethroughSpan
import android.text.style.UnderlineSpan
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.foundation.text.selection.DisableSelection
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import io.github.ktclat.c001apk.nativeapp.platform.AppLink
import io.github.ktclat.c001apk.nativeapp.platform.parseAppLink

internal val LocalShowEmoticons = staticCompositionLocalOf { true }

/** Decoded source offsets remain unchanged: selection, accessibility and Copy keep exact tokens. */
internal fun postAnnotatedText(spanned: Spanned): AnnotatedString = withEmoticonLiteralContext(AnnotatedString.Builder(spanned.toString()).apply {
    for (span in spanned.getSpans(0, spanned.length, Any::class.java)) {
        val start = spanned.getSpanStart(span); val end = spanned.getSpanEnd(span)
        if (start < 0 || end <= start || end > length) continue
        when (span) {
            is TypefaceSpan -> if (span.family == "monospace") addStyle(SpanStyle(fontFamily = FontFamily.Monospace), start, end)
            is StyleSpan -> addStyle(SpanStyle(
                fontWeight = if (span.style and Typeface.BOLD != 0) FontWeight.Medium else null,
                fontStyle = if (span.style and Typeface.ITALIC != 0) FontStyle.Italic else null), start, end)
            is UnderlineSpan -> addStyle(SpanStyle(textDecoration = TextDecoration.Underline), start, end)
            is StrikethroughSpan -> addStyle(SpanStyle(textDecoration = TextDecoration.LineThrough), start, end)
        }
    }
}.toAnnotatedString())

internal data class InlineRichContent(val text: AnnotatedString, val tokens: Set<String>)

/** Bounded text scan; code spans indexed once, never rescanned per unknown marker. No URL attributes. */
internal fun inlineRichContent(source: AnnotatedString, showEmoticons: Boolean): InlineRichContent {
    if (!showEmoticons) return InlineRichContent(source, emptySet())
    val result = AnnotatedString.Builder()
    val tokens = linkedSetOf<String>()
    val codeRanges = (source.spanStyles.filter { it.item.fontFamily == FontFamily.Monospace }.map { it.start to it.end } +
        source.getStringAnnotations(EMOTICON_LITERAL_CONTEXT, 0, source.length).map { it.start to it.end }).sortedBy { it.first }
    var codeAt = 0
    var cursor = 0; var plainStart = 0; var count = 0
    while (cursor < source.length && count < InlineEmoticons.MAX_PER_TEXT) {
        if (source[cursor] != '[') { cursor++; continue }
        // Flutter consumes a whole candidate through its first closing bracket, even if unknown.
        // Advance over an overlong/nested candidate once, without allocating its substring.
        val close = source.text.indexOf(']', cursor + 1)
        if (close < 0) break
        if (close + 1 - cursor > InlineEmoticons.maxTokenLength) { cursor = close + 1; continue }
        val token = source.text.substring(cursor, close + 1)
        if (!InlineEmoticons.contains(token)) { cursor = close + 1; continue }
        while (codeAt < codeRanges.size && codeRanges[codeAt].second <= cursor) codeAt++
        val code = codeAt < codeRanges.size && codeRanges[codeAt].first < close + 1
        if (code) { cursor = close + 1; continue }
        result.append(source.subSequence(plainStart, cursor))
        // appendInlineContent supplies the official Foundation annotation, using the original token
        // as alternate text. Restore source spans over it without substituting U+FFFC or losing labels.
        val start = result.length
        result.appendInlineContent(token, token)
        source.subSequence(cursor, close + 1).spanStyles.forEach { result.addStyle(it.item, start + it.start, start + it.end) }
        source.subSequence(cursor, close + 1).getStringAnnotations(0, token.length).forEach {
            result.addStringAnnotation(it.tag, it.item, start + it.start, start + it.end)
        }
        tokens.add(token); count++
        cursor = close + 1; plainStart = cursor
    }
    result.append(source.subSequence(plainStart, source.length))
    return InlineRichContent(result.toAnnotatedString(), tokens)
}

/** Links only get native callbacks; no UriHandler/default browser execution or inferred hashtag URLs. */
internal fun linkedRichText(source: AnnotatedString, links: List<PostLink>, color: Color,
    openLink: (String) -> Unit): AnnotatedString = AnnotatedString.Builder(source).apply {
    links.take(128).forEach { link ->
        val start = link.start.coerceAtLeast(0); val end = link.end.coerceAtMost(source.length)
        if (start < end && link.url.length <= 4096 && parseAppLink(link.url) !is AppLink.Unsupported) {
            addLink(LinkAnnotation.Clickable(link.url,
                TextLinkStyles(style = SpanStyle(color = color, textDecoration = TextDecoration.None))) { openLink(link.url) }, start, end)
        }
    }
}.toAnnotatedString()

private class RichTextLinkOwner { var active = true }

@Composable
internal fun NativeRichText(text: AnnotatedString, modifier: Modifier = Modifier, style: TextStyle,
    links: List<PostLink> = emptyList(), openLink: (String) -> Unit = {},
    expandEmoticons: Boolean = true) {
    val show = LocalShowEmoticons.current && expandEmoticons
    val rich = remember(text, show) { inlineRichContent(text, show) }
    val currentOpen by rememberUpdatedState(openLink)
    val owner = remember(text, links) { RichTextLinkOwner() }
    val currentOwner by rememberUpdatedState(owner)
    DisposableEffect(owner) { onDispose { owner.active = false } }
    val primary = MaterialTheme.colorScheme.primary
    val linked = remember(rich.text, links, primary, owner) {
        linkedRichText(rich.text, links, primary) {
            // A retained immutable span from replaced/disposed content has no current authority.
            if (owner.active && currentOwner === owner) currentOpen(it)
        }
    }
    val inline = rich.tokens.associateWith { token ->
        val label = token in InlineEmoticons.labels
        InlineTextContent(Placeholder(if (label) 1.8.em else 1.3.em, 1.3.em, PlaceholderVerticalAlign.TextCenter)) {
            // The parent owns selectable/accessibility alternate text. Decorative child Text
            // must not register a second selectable, or Select all appends the badge labels.
            DisableSelection {
                val glyph = Modifier.fillMaxSize().testTag("inline-emoticon:$token").clearAndSetSemantics { }
                val image = InlineEmoticons.images[token]
                when {
                    image != null -> Image(painterResource(image), null, glyph)
                    token == InlineEmoticons.PHOTO -> Icon(FlutterMaterialIcons.PhotoOutlined, null, glyph, tint = primary)
                    label -> Box(glyph.border(1.dp, primary, RoundedCornerShape(4.dp)), contentAlignment = Alignment.Center) {
                        Text(token.removeSurrounding("[", "]"), style = style.copy(fontSize = style.fontSize * .73f,
                            lineHeight = style.fontSize, fontWeight = FontWeight.Bold), color = primary, maxLines = 1)
                    }
                }
            }
        }
    }
    Text(linked, modifier, style = style, inlineContent = inline)
}
