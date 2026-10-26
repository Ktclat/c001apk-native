// SPDX-License-Identifier: AGPL-3.0-only
package io.github.ktclat.c001apk.nativeapp.settings

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w393dp-h852dp-420dpi")
class AppearanceSettingsUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private var settings by mutableStateOf(AppSettings(dynamicColor = false))
    private val edits = mutableListOf<(AppSettings) -> AppSettings>()
    private lateinit var restorer: StateRestorationTester
    private fun render(systemScale: Float = 1f, apply: Boolean = true) {
        restorer = StateRestorationTester(compose)
        restorer.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, systemScale * settings.fontScale)) {
                MaterialTheme(typography = appTypography(settings.font)) {
                    SettingsScreen(settings, {}, {}, {}, {}, {}, {}, onSettingsEdit = {
                        edits += it
                        if (apply) settings = it(settings)
                    })
                }
            }
        }
        compose.onNodeWithText("外观与字体").performScrollTo().performClick()
    }
    private fun open(title: String) {
        compose.onNodeWithTag("settings-detail-list").performScrollToNode(hasText(title) and hasClickAction())
        compose.onNode(hasText(title) and hasClickAction()).performClick()
    }
    private fun setScale(value: Float) {
        compose.onNodeWithContentDescription("应用字号").performScrollTo().performSemanticsAction(SemanticsActions.SetProgress) { it(value) }
    }
    private fun layout(tag: String): TextLayoutResult {
        val result = mutableListOf<TextLayoutResult>()
        compose.onNodeWithTag(tag, useUnmergedTree = true).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(result) }
        return result.single()
    }
    @Test fun fontChoicesHaveIndependentSamplesAndWholeRowRadioActions() {
        render(); open("界面字体")
        FontChoice.entries.forEach { font ->
            val choice = compose.onNodeWithTag("font-choice-${font.name}")
            choice.performScrollTo().assertHeightIsAtLeast(48.dp)
            if (font == settings.font) choice.assertIsSelected() else choice.assertIsNotSelected()
            val style = layout("font-sample-${font.name}").layoutInput.style
            assertEquals(fontComparisonStyle(font).fontFamily, style.fontFamily)
            assertEquals(fontComparisonStyle(font).fontSize, style.fontSize)
            assertEquals(fontComparisonStyle(font).lineHeight, style.lineHeight)
        }
        compose.onNodeWithTag("font-choice-MATERIAL").performScrollTo().performClick()
        compose.onNodeWithTag("appearance-detail").assertDoesNotExist()
        assertEquals(FontChoice.MATERIAL, settings.font); assertEquals(1, edits.size)
        open("界面字体")
        compose.onNodeWithTag("font-choice-MATERIAL").assertIsSelected()
        compose.onNodeWithContentDescription("关闭界面字体").performClick()
        assertEquals(1, edits.size)
    }
    @Test fun readingSamplesUseEachPresetAndCurrentFontAndSelectionKeepsOtherSettings() {
        settings = settings.copy(font = FontChoice.SYSTEM, fontScale = 1.15f, theme = ThemeMode.DARK)
        render(); open("正文排版")
        ReadingPreset.entries.forEach { preset ->
            compose.onNodeWithTag("reading-choice-${preset.name}").performScrollTo()
            val sample = layout("reading-body-${preset.name}").layoutInput.style
            val expected = readingStyles(preset, FontChoice.SYSTEM).body
            assertEquals(expected.fontFamily, sample.fontFamily); assertEquals(expected.fontSize, sample.fontSize)
            assertEquals(expected.lineHeight, sample.lineHeight)
        }
        compose.onNodeWithTag("reading-choice-ORIGINAL").performClick()
        assertEquals(ReadingPreset.ORIGINAL, settings.readingPreset)
        assertEquals(FontChoice.SYSTEM, settings.font); assertEquals(ThemeMode.DARK, settings.theme)
        assertEquals(1.15f, settings.fontScale)
    }
    @Test fun sizePreviewChangesActualTextDensityButCancelCloseAndBackNeverWrite() {
        settings = settings.copy(fontScale = 1.2f)
        render(systemScale = 2f)
        for (dismiss in 0..2) {
            open("应用字号"); setScale(1.6f)
            val layouts = mutableListOf<TextLayoutResult>()
            compose.onNode(hasTestTag("settings-preview-body") and hasAnyAncestor(hasTestTag("appearance-detail")), useUnmergedTree = true)
                .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            assertEquals(3.2f, layouts.single().layoutInput.density.fontScale, .001f)
            when (dismiss) {
                0 -> compose.onNodeWithText("取消").performScrollTo().performClick()
                1 -> compose.onNodeWithContentDescription("关闭应用字号").performClick()
                else -> compose.runOnUiThread { org.robolectric.shadows.ShadowDialog.getLatestDialog().onBackPressed() }
            }
            compose.onNodeWithTag("appearance-detail").assertDoesNotExist()
            assertEquals(1.2f, settings.fontScale); assertTrue(edits.isEmpty())
        }
        open("应用字号"); compose.onNodeWithText("预览字号：120%").assertExists()
    }
    @Test fun sizeDraftSurvivesRecreationAndUnrelatedEmissionApplyIsOneFieldIntent() {
        render(apply = false); open("应用字号"); setScale(1.35f)
        compose.runOnIdle { settings = settings.copy(theme = ThemeMode.DARK, recordHistory = false) }
        restorer.emulateSavedInstanceStateRestore()
        compose.onNodeWithText("预览字号：135%").assertExists()
        compose.onNodeWithText("应用").performScrollTo().performTouchInput { repeat(3) { click() } }
        compose.onNodeWithTag("appearance-detail").assertDoesNotExist()
        assertEquals(1, edits.size)
        val result = edits.single()(settings.copy(font = FontChoice.SYSTEM))
        assertEquals(1.35f, result.fontScale); assertEquals(ThemeMode.DARK, result.theme)
        assertEquals(FontChoice.SYSTEM, result.font); assertFalse(result.recordHistory)
    }
    @Test @Config(qualifiers = "w393dp-h420dp-420dpi")
    fun dismissingDetailsRetainsExactNonzeroSettingsScrollAndAnchor() {
        settings = settings.copy(fontScale = 1.4f)
        render()
        val list = compose.onNodeWithTag("settings-detail-list")
        list.performScrollToIndex(5)
        val beforeOpen = list.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()
        assertTrue("Short-window fixture must be scrolled before opening: $beforeOpen", beforeOpen > 0f)
        compose.onNode(hasText("应用字号") and hasClickAction()).performClick()
        val before = list.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()
        val anchor = compose.onNode(hasText("应用字号") and hasClickAction()).getUnclippedBoundsInRoot()
        assertEquals("Opening must retain the same parent scroll", beforeOpen, before, .001f)
        setScale(1.6f)
        compose.onNodeWithText("取消").performScrollTo().performClick()
        assertEquals(before, list.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value(), .001f)
        assertEquals(anchor, compose.onNode(hasText("应用字号") and hasClickAction()).getUnclippedBoundsInRoot())
        assertTrue(edits.isEmpty())
    }

    @Test fun resetOnlyChangesDraftAndApplyReopenReflectsSelectionWithoutLosingParentScroll() {
        settings = settings.copy(fontScale = 1.4f)
        render(); open("应用字号")
        compose.onNodeWithText("恢复默认字号").performScrollTo().performClick()
        assertEquals(1.4f, settings.fontScale); assertTrue(edits.isEmpty())
        compose.onNodeWithText("应用").performScrollTo().performClick()
        assertEquals(1f, settings.fontScale); assertEquals(1, edits.size)
        open("应用字号"); compose.onNodeWithText("预览字号：100%").assertExists()
        compose.onNodeWithContentDescription("关闭应用字号").performClick()
        open("界面字体")
        val before = compose.onNodeWithTag("settings-detail-list").fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()
        compose.onNodeWithContentDescription("关闭界面字体").performClick()
        assertEquals(before, compose.onNodeWithTag("settings-detail-list").fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value(), .001f)
    }
}
