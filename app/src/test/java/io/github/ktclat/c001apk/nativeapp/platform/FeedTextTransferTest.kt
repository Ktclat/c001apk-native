// SPDX-License-Identifier: AGPL-3.0-only
package io.github.ktclat.c001apk.nativeapp.platform

import android.app.Activity
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class FeedTextTransferTest {
    @Test fun clipboardReceivesOnlyOnePlainLinkAndNoIntent() {
        Robolectric.buildActivity(Activity::class.java).setup().use { host ->
            val activity = host.get()
            AndroidFeedTextTransfer(activity).copyLink("https://www.coolapk1s.com/feed/123")
            val clip = (activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).primaryClip!!
            assertEquals(1, clip.itemCount); assertEquals("text/plain", clip.description.getMimeType(0))
            assertEquals("https://www.coolapk1s.com/feed/123", clip.getItemAt(0).text.toString())
            assertNull(clip.getItemAt(0).intent); assertNull(clip.getItemAt(0).uri)
            assertNull(shadowOf(activity).nextStartedActivity)
        }
    }
    @Test fun shareLaunchesChooserWithOnePlainTextExtraNoRecipientOrPermissions() {
        Robolectric.buildActivity(Activity::class.java).setup().use { host ->
            val activity = host.get()
            AndroidFeedTextTransfer(activity).shareText("synthetic text\nhttps://www.coolapk.com/feed/123")
            val chooser = shadowOf(activity).nextStartedActivity!!
            assertEquals(Intent.ACTION_CHOOSER, chooser.action)
            @Suppress("DEPRECATION") val send = chooser.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!
            assertEquals(Intent.ACTION_SEND, send.action); assertEquals("text/plain", send.type)
            assertEquals(setOf(Intent.EXTRA_TEXT), send.extras!!.keySet())
            assertEquals("synthetic text\nhttps://www.coolapk.com/feed/123", send.getStringExtra(Intent.EXTRA_TEXT))
            assertEquals(0, send.flags); assertNull(send.`package`); assertNull(send.component)
            assertNull(send.data)
            // Android can migrate EXTRA_TEXT to ClipData while preparing ACTION_SEND to leave
            // the process. Inspect that platform-created item instead of assuming it is absent.
            val clip = send.clipData!!
            assertEquals(1, clip.itemCount); assertEquals(1, clip.description.mimeTypeCount)
            assertEquals("text/plain", clip.description.getMimeType(0))
            assertEquals(send.getStringExtra(Intent.EXTRA_TEXT), clip.getItemAt(0).text.toString())
            assertNull(clip.getItemAt(0).uri); assertNull(clip.getItemAt(0).intent); assertNull(clip.getItemAt(0).htmlText)
            assertEquals(0, chooser.flags); assertNull(chooser.data); assertNull(chooser.`package`); assertNull(chooser.component)
            assertNull(shadowOf(activity).nextStartedActivity)
        }
    }
}
