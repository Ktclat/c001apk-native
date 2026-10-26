// SPDX-License-Identifier: AGPL-3.0-only
package io.github.ktclat.c001apk.nativeapp.platform

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent

/** Only the prepared public text crosses this boundary. No account, request, or URI grants. */
internal interface FeedTextTransfer {
    fun copyLink(link: String)
    fun shareText(text: String)
}

internal class AndroidFeedTextTransfer(private val context: Context) : FeedTextTransfer {
    override fun copyLink(link: String) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            ?: error("Clipboard unavailable")
        clipboard.setPrimaryClip(ClipData.newPlainText("动态链接", link))
    }

    override fun shareText(text: String) {
        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
        // Let the user choose or cancel. Never select a package or claim that a post was sent.
        context.startActivity(Intent.createChooser(send, "分享动态"))
    }
}
