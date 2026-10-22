// SPDX-License-Identifier: AGPL-3.0-only
package io.github.ktclat.c001apk.nativeapp.platform

import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.ContinuationInterceptor

/** Real IO and Android Main dispatchers, never Dispatchers.setMain or an unconfined test scope. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class InstalledAppsPublicationTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun successfulInventoryLoadsOnRealIoAndPublishesOneCopiedSnapshotOnMain() {
        val source = mutableListOf(app("Loaded"))
        val state = mutableStateOf(InstalledAppsLoadState())
        val publications = CopyOnWriteArrayList<InstalledAppsLoadState>()
        val loaded = AtomicBoolean()
        compose.setContent {
            LaunchedEffect(Unit) {
                loadAndPublishInstalledApps(state.value, load = {
                    assertIo()
                    loaded.set(true)
                    source
                }) {
                    assertMain()
                    publications += it
                    state.value = it
                }
            }
            Text(label(state.value))
        }
        awaitText("Loaded")
        assertTrue(loaded.get())
        assertEquals(2, publications.size)
        assertNull(publications[0].apps)
        assertFalse(publications[0].failed)
        assertEquals(listOf(app("Loaded")), publications[1].apps)
        source.clear()
        compose.runOnIdle { assertEquals("Published inventory must not alias loader list", 1, state.value.apps?.size) }
        compose.onNodeWithText("Loaded").assertExists()
    }

    @Test fun emptySuccessIsDistinctFromLoadingAndClearsPreviousError() {
        val state = mutableStateOf(InstalledAppsLoadState(failed = true))
        compose.setContent {
            LaunchedEffect(Unit) {
                loadAndPublishInstalledApps(state.value, load = { assertIo(); emptyList() }) {
                    assertMain(); state.value = it
                }
            }
            Text(label(state.value))
        }
        awaitText("empty")
        compose.runOnIdle { assertEquals(InstalledAppsLoadState(emptyList()), state.value) }
    }

    @Test fun refreshClearsErrorButRetainsOldRowsAndFailureRetainsSameInventory() {
        val old = listOf(app("Previous"))
        val state = mutableStateOf(InstalledAppsLoadState(old, failed = true))
        val entered = AtomicBoolean()
        val release = CountDownLatch(1)
        val publications = CopyOnWriteArrayList<InstalledAppsLoadState>()
        compose.setContent {
            LaunchedEffect(Unit) {
                loadAndPublishInstalledApps(state.value, load = {
                    assertIo()
                    entered.set(true)
                    check(release.await(10, TimeUnit.SECONDS)) { "Test did not release loader" }
                    throw IOException("Synthetic unavailable inventory")
                }) {
                    assertMain(); publications += it; state.value = it
                }
            }
            Text(label(state.value))
            Text(if (state.value.failed) "failed" else "clear")
        }
        try {
            compose.waitUntil(5_000) { entered.get() }
            compose.onNodeWithText("Previous").assertExists()
            compose.onNodeWithText("clear").assertExists()
            assertEquals(listOf(InstalledAppsLoadState(old)), publications.toList())
            release.countDown()
            awaitText("failed")
            compose.onNodeWithText("Previous").assertExists()
            assertEquals(2, publications.size)
            assertEquals(InstalledAppsLoadState(old, failed = true), publications.last())
        } finally { release.countDown() }
    }

    @Test fun disposingCompositionCancelsPublicationAfterNonCooperativeIoReturns() {
        val show = mutableStateOf(true)
        val entered = AtomicBoolean()
        val completed = AtomicBoolean()
        val returned = AtomicBoolean()
        val publications = AtomicInteger()
        val release = CountDownLatch(1)
        compose.setContent {
            if (show.value) {
                LaunchedEffect(Unit) {
                    try {
                        loadAndPublishInstalledApps(InstalledAppsLoadState(), load = {
                            assertIo()
                            entered.set(true)
                            check(release.await(10, TimeUnit.SECONDS)) { "Test did not release loader" }
                            returned.set(true)
                            listOf(app("Cancelled"))
                        }) { assertMain(); publications.incrementAndGet() }
                    } finally { completed.set(true) }
                }
                Text("mounted")
            } else Text("removed")
        }
        try {
            compose.waitUntil(5_000) { entered.get() }
            assertEquals("Only initial refresh state was published", 1, publications.get())
            compose.runOnIdle { show.value = false }
            compose.onNodeWithText("removed").assertExists()
            release.countDown()
            compose.waitUntil(5_000) { completed.get() && returned.get() }
            assertEquals("Cancelled inventory must not publish ready or failed", 1, publications.get())
        } finally { release.countDown() }
    }

    @Test fun replacedRequestCannotOverwriteLatestInventoryWhenOldIoFinallyReturns() {
        val request = mutableStateOf(0)
        val state = mutableStateOf(InstalledAppsLoadState(listOf(app("Previous"))))
        val entered = AtomicBoolean()
        val oldCompleted = AtomicBoolean()
        val returned = AtomicBoolean()
        val release = CountDownLatch(1)
        val terminalLabels = CopyOnWriteArrayList<String>()
        compose.setContent {
            val generation = request.value
            LaunchedEffect(generation) {
                try {
                    loadAndPublishInstalledApps(state.value, load = {
                        assertIo()
                        if (generation == 0) {
                            entered.set(true)
                            check(release.await(10, TimeUnit.SECONDS)) { "Test did not release old loader" }
                            returned.set(true)
                            listOf(app("Obsolete"))
                        } else listOf(app("Latest"))
                    }) {
                        assertMain(); state.value = it
                        terminalLabels += label(it)
                    }
                } finally { if (generation == 0) oldCompleted.set(true) }
            }
            Text(label(state.value))
        }
        try {
            compose.waitUntil(5_000) { entered.get() }
            compose.onNodeWithText("Previous").assertExists()
            compose.runOnIdle { request.value = 1 }
            awaitText("Latest")
            release.countDown()
            compose.waitUntil(5_000) { oldCompleted.get() && returned.get() }
            compose.waitForIdle()
            compose.onNodeWithText("Latest").assertExists()
            compose.onAllNodesWithText("Obsolete").assertCountEquals(0)
            assertFalse(terminalLabels.contains("Obsolete"))
            compose.runOnIdle { assertFalse(state.value.failed) }
        } finally { release.countDown() }
    }

    private fun app(label: String) = InstalledApp("synthetic.publication", label)
    private fun label(state: InstalledAppsLoadState): String =
        state.apps?.firstOrNull()?.label ?: if (state.apps == null) "pending" else "empty"
    private suspend fun assertIo() {
        assertSame("Loader must use real IO dispatcher", Dispatchers.IO, currentCoroutineContext()[ContinuationInterceptor])
        assertNotSame("Loader must not run on Android UI Looper", Looper.getMainLooper(), Looper.myLooper())
    }
    private fun assertMain() {
        assertSame("Every state publication must run on Android Main", Looper.getMainLooper(), Looper.myLooper())
    }
    private fun awaitText(text: String) {
        compose.waitUntil(5_000) { compose.onAllNodesWithText(text).fetchSemanticsNodes().size == 1 }
        compose.waitForIdle()
    }
}
