// SPDX-License-Identifier: AGPL-3.0-only
package io.github.ktclat.c001apk.nativeapp.social

import android.net.Uri
import android.os.Looper
import io.github.ktclat.c001apk.nativeapp.account.AccountStore
import io.github.ktclat.c001apk.nativeapp.account.CredentialPersistence
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@OptIn(ExperimentalCoroutinesApi::class)
class ComposerEmoticonImageOwnershipTest {
    private val dispatcher = StandardTestDispatcher()
    private lateinit var repository: SocialRepository
    private lateinit var model: ComposerViewModel
    @Before fun setup() {
        Dispatchers.setMain(dispatcher)
        val accounts = AccountStore(object : CredentialPersistence {
            override fun read(): ByteArray? = null
            override fun write(bytes: ByteArray) = Unit
            override fun reset() = Unit
        })
        repository = SocialRepository(accounts) { error("No text-edit transport") }
        model = ComposerViewModel(ComposerDraft("keep"), accounts.state.value.generation)
    }
    @After fun cleanup() {
        try {
            model.abandon()
            // Abandon writes Compose state. Deliver already-due Main callbacks before Robolectric
            // resets its queue, so a later Compose test cannot inherit a stranded notification.
            // idle() does not advance time or privately reset/flush Compose snapshot machinery.
            shadowOf(Looper.getMainLooper()).idle()
        } finally {
            Dispatchers.resetMain()
        }
    }
    @Test fun canceledEmptyResultUnlocksAndKeepsExactInput() {
        val before = model.input
        assertTrue(model.beginImagePicker())
        model.select(RuntimeEnvironment.getApplication(), emptyList())
        assertTrue(model.editable()); assertFalse(model.awaitingImagePicker); assertEquals(before, model.input)
        assertTrue(model.insertTextEmoticon("[笑哭]", repository))
    }
    @Test fun stagingAlsoRejectsInsertionAndReadFailureRestoresEditing() = runBlocking {
        model.beginImagePicker()
        model.select(RuntimeEnvironment.getApplication(), listOf(Uri.parse("https://synthetic.invalid/never-read")))
        assertTrue(model.selecting); assertFalse(model.awaitingImagePicker)
        assertFalse(model.insertTextEmoticon("[笑哭]", repository)); assertEquals("keep", model.text)
        // Main is test-controlled but image validation uses real Dispatchers.IO. Service the owned
        // scheduler until the IO completion returns, rather than treating virtual idleness as done.
        withTimeout(5_000) {
            while (model.selecting) { dispatcher.scheduler.runCurrent(); delay(10) }
        }
        assertFalse(model.selecting); assertTrue(model.editable()); assertNotNull(model.error)
    }
    @Test fun abandonedPendingPickerResultNeverRevivesOwnerOrTouchesNewDraft() {
        val before = model
        before.beginImagePicker(); before.abandon()
        before.select(RuntimeEnvironment.getApplication(), listOf(Uri.parse("content://synthetic.invalid/stale")))
        assertFalse(before.selecting); assertFalse(before.awaitingImagePicker)
        assertFalse(before.insertTextEmoticon("[笑哭]", repository)); assertEquals("", before.text)
        val fresh = ComposerViewModel(ComposerDraft("fresh"), 0)
        try { assertTrue(fresh.editable()); assertEquals("fresh", fresh.text); assertTrue(fresh.images.isEmpty()) }
        finally { fresh.abandon() }
    }
}
