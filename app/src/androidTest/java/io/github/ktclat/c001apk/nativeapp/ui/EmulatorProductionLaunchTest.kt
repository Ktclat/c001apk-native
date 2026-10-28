// SPDX-License-Identifier: AGPL-3.0-only
// Unmodified MainActivity and real persisted task preferences; no fixture account is persisted.
package io.github.ktclat.c001apk.nativeapp.ui

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.ktclat.c001apk.nativeapp.MainActivity
import io.github.ktclat.c001apk.nativeapp.NativeApplication
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class EmulatorProductionLaunchTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    @Test fun actualEmotePreferenceToggleSurvivesReentryAndRecreation() = recordPrimaryTestFailure("production-prefs") {
        val settings = (compose.activity.application as NativeApplication).container.settings
        val original = runBlocking { settings.settings.first().showEmoji }
        var primary: Throwable? = null
        try {
            openContent()
            val toggle = compose.onNode(isToggleable() and hasText("显示表情图片"))
            if (original) toggle.assertIsOn().performClick() else toggle.assertIsOff()
            awaitPreference(false); toggle.assertIsOff()
            EmulatorEvidence.capture("production-emotes-01-off", expectedText = "显示表情图片")
            back(); back(); openContent()
            toggle.assertIsOff(); EmulatorEvidence.capture("production-emotes-02-reentry-off", expectedText = "显示表情图片")
            compose.activityRule.scenario.recreate()
            awaitLabel("显示表情图片"); awaitPreference(false)
            toggle.assertIsOff(); EmulatorEvidence.capture("production-emotes-03-recreated-off", expectedText = "显示表情图片")
            toggle.performClick(); awaitPreference(true); toggle.assertIsOn()
            EmulatorEvidence.capture("production-emotes-04-on", expectedText = "显示表情图片")
            EmulatorEvidence.text("production-emotes-persistence.txt", "original=$original\noffAfterReentry=true\noffAfterRecreation=true\nonAfterVisibleToggle=true\nScope=task persisted showEmoji only, no live-feed success claim")
        } catch (error: Throwable) { primary = error; throw error }
        finally {
            try { runBlocking { settings.update { it.copy(showEmoji = original) } }
                assertEquals(original, runBlocking { settings.settings.first().showEmoji })
                EmulatorEvidence.text("production-emotes-restored.txt", "showEmoji=$original")
            } catch (cleanup: Throwable) { if (primary != null) primary.addSuppressed(cleanup) else throw cleanup }
        }
    }
    private fun openContent() {
        compose.waitUntil(30_000) { compose.onAllNodes(hasText("我的") and isSelectable()).fetchSemanticsNodes().size == 1 }
        compose.onNode(hasText("我的") and isSelectable()).assertIsDisplayed().performClick()
        awaitLabel("我的") // allows dashboard header+navigation duplicates; only readiness, not a click target
        compose.onNodeWithContentDescription("设置").assertIsDisplayed().performClick()
        compose.waitUntil(30_000) { compose.onAllNodesWithTag("settings-category-list").fetchSemanticsNodes().size == 1 }
        compose.onNodeWithTag("settings-category-list").performScrollToNode(hasText("关注与内容"))
        compose.onNodeWithText("关注与内容").assertIsDisplayed().performClick(); awaitLabel("显示表情图片")
    }
    private fun awaitLabel(text: String) = compose.waitUntil(30_000) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
    private fun awaitPreference(expected: Boolean) = compose.waitUntil(30_000) {
        runBlocking { (compose.activity.application as NativeApplication).container.settings.settings.first().showEmoji } == expected
    }
    private fun back() { compose.onNodeWithContentDescription("返回").performClick(); compose.waitForIdle() }
}
