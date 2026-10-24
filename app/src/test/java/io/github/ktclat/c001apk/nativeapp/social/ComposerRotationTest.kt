// SPDX-License-Identifier: AGPL-3.0-only
package io.github.ktclat.c001apk.nativeapp.social

import android.content.res.Configuration
import android.os.Bundle
import android.os.Looper
import android.os.Handler
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.runtime.snapshots.ObserverHandle
import androidx.lifecycle.LifecycleEventObserver
import org.robolectric.Shadows.shadowOf
import java.util.concurrent.atomic.AtomicBoolean
import androidx.activity.ComponentActivity
import androidx.lifecycle.Lifecycle
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import io.github.ktclat.c001apk.nativeapp.account.AccountStore
import io.github.ktclat.c001apk.nativeapp.account.CredentialPersistence
import org.junit.*
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

class RotationComposerActivity : ComponentActivity() {
    companion object { var repository: SocialRepository? = null }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { FeedComposerUi(requireNotNull(repository), 1, ComposerDraft("")) } }
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ComposerRotationTest {
    @get:Rule val compose = createEmptyComposeRule()
    @After fun clearFixture() { RotationComposerActivity.repository = null }
    @Test fun activityConfigurationChangeKeepsDraftAndVisibilityWithoutSending() {
        val accounts = AccountStore(object : CredentialPersistence {
            override fun read(): ByteArray? = null
            override fun write(bytes: ByteArray) = error("No credential write")
            override fun reset() = Unit
        })
        RotationComposerActivity.repository = SocialRepository(accounts) { error("Editing cannot request transport") }
        val controller = Robolectric.buildActivity(RotationComposerActivity::class.java).setup()
        var primaryFailure: Throwable? = null
        var teardownNotification: AtomicBoolean? = null
        var teardownObserver: ObserverHandle? = null
        try {
            compose.onNode(hasSetTextAction()).performTextInput("rotation draft")
            compose.onNode(isToggleable()).performClick().assertIsOn()
            val originalActivity = controller.get()
            compose.runOnIdle {
                val configuration = Configuration(controller.get().resources.configuration)
                configuration.orientation = if (configuration.orientation == Configuration.ORIENTATION_LANDSCAPE)
                    Configuration.ORIENTATION_PORTRAIT else Configuration.ORIENTATION_LANDSCAPE
                controller.configurationChange(configuration).visible()
            }
            assertNotSame(originalActivity, controller.get())
            assertTrue(originalActivity.isDestroyed)
            assertEquals(Lifecycle.State.RESUMED, controller.get().lifecycle.currentState)
            assertTrue("Recreated window must be attached before querying Compose", controller.get().window.decorView.isAttachedToWindow)
            compose.waitForIdle()
            compose.onNode(hasSetTextAction()).assertTextContains("rotation draft").assertIsEnabled()
            compose.onNode(isToggleable()).assertIsOn()
            compose.onNodeWithText("确认发送？").assertDoesNotExist()
            // Regression: actual Activity destruction dirties a snapshot. The queued manager must
            // apply it before this fixture returns, rather than leave its callback for a reset queue.
            val destroyed = mutableStateOf(false)
            val applied = AtomicBoolean(false)
            teardownNotification = applied
            teardownObserver = Snapshot.registerApplyObserver { changed, _ ->
                if (destroyed in changed) applied.set(true)
            }
            controller.get().lifecycle.addObserver(LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_DESTROY) destroyed.value = true
            })
            assertFalse("Teardown marker has not been written before destruction", applied.get())
        } catch (failure: Throwable) {
            primaryFailure = failure
            throw failure
        } finally {
            try {
                // Cleanup must not first demand Compose idleness after a failed root-readiness assertion.
                InstrumentationRegistry.getInstrumentation().runOnMainSync {
                    val queuedTeardownDelivery = AtomicBoolean(false)
                    var teardownPostAccepted = false
                    var sentinelPendingBeforeDelivery = false
                    destroyFixtureAndDeliverQueuedMainWork {
                        try { controller.pause().stop().destroy() }
                        finally {
                            teardownPostAccepted = Handler(Looper.getMainLooper()).post { queuedTeardownDelivery.set(true) }
                            sentinelPendingBeforeDelivery = !queuedTeardownDelivery.get()
                        }
                    }
                    if (primaryFailure == null) {
                        assertTrue("Main handler must accept teardown completion", teardownPostAccepted)
                        assertTrue("Teardown sentinel must still be queued before delivery", sentinelPendingBeforeDelivery)
                        assertTrue("Queued teardown work must execute before framework queue reset", queuedTeardownDelivery.get())
                        assertTrue("Destruction snapshot must be applied before framework queue reset", requireNotNull(teardownNotification).get())
                    }
                }
            } catch (cleanupFailure: Throwable) {
                if (primaryFailure != null) primaryFailure.addSuppressed(cleanupFailure) else throw cleanupFailure
            } finally {
                teardownObserver?.dispose()
            }
        }
    }
}

/** Deliver only already-due tasks posted by destruction, before Robolectric clears its Main queue. */
private fun destroyFixtureAndDeliverQueuedMainWork(destroy: () -> Unit) {
    var destructionFailure: Throwable? = null
    try { destroy() } catch (failure: Throwable) { destructionFailure = failure }
    try {
        // idle(), unlike idleFor/runToEndOfTasks, does not advance time to future scheduled work.
        // This uses normal dispatcher delivery, not Snapshot.sendApplyNotifications or private resets.
        shadowOf(Looper.getMainLooper()).idle()
    } catch (deliveryFailure: Throwable) {
        if (destructionFailure != null) destructionFailure.addSuppressed(deliveryFailure)
        else throw deliveryFailure
    }
    destructionFailure?.let { throw it }
}
