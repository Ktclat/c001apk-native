// SPDX-License-Identifier: AGPL-3.0-only
package io.github.ktclat.c001apk.nativeapp.ui

import androidx.compose.material3.LocalTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow

/** Literal summary text, not HTML/Markdown. Only the closed bundled token catalog is decorative.
 * No parser, link discovery/callback, URL fetch, account access, or clipboard mutation is introduced.
 * Original line/ellipsis policy and exact parent text remain controlled by the existing caller.
 */
@Composable
internal fun NativeTokenText(text: String, modifier: Modifier = Modifier, style: TextStyle = LocalTextStyle.current,
    color: Color = Color.Unspecified, maxLines: Int = Int.MAX_VALUE, overflow: TextOverflow = TextOverflow.Clip) {
    val source = remember(text) { AnnotatedString(text) }
    NativeRichText(source, modifier, if (color == Color.Unspecified) style else style.copy(color = color),
        maxLines = maxLines, overflow = overflow)
}
