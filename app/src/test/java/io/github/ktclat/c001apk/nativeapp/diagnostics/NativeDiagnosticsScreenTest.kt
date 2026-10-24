// SPDX-License-Identifier: AGPL-3.0-only
package io.github.ktclat.c001apk.nativeapp.diagnostics

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w393dp-h240dp-420dpi")
class NativeDiagnosticsScreenTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun onlyExplicitCopyExportsLatestReportAndBackStillWorks() {
        var copies = 0
        var copied: String? = null
        var backs = 0
        val current = mutableStateOf(sampleDiagnostics())
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                MaterialTheme { NativeDiagnosticsContent(current.value, { backs++ }, { copies++; copied = it }) }
            }
        }
        compose.waitForIdle()
        assertEquals(0, copies)
        compose.onNodeWithTag("copy-native-diagnostics").assertIsDisplayed().performClick()
        assertEquals(1, copies)
        assertEquals(current.value.copyText(), copied)
        compose.runOnIdle { current.value = current.value.copy(widthPx = 720, touchExploration = true) }
        compose.waitForIdle()
        assertEquals(1, copies)
        compose.onNodeWithTag("copy-native-diagnostics").performClick()
        assertEquals(2, copies)
        assertEquals(current.value.copyText(), copied)
        compose.onNodeWithText("返回").performClick()
        assertEquals(1, backs)
        assertEquals(2, copies)
    }
}
