// SPDX-License-Identifier: AGPL-3.0-only
package io.github.ktclat.c001apk.nativeapp.settings

import androidx.compose.ui.text.font.FontWeight
import org.junit.Assert.*
import org.junit.Test

class ThemeTypographyTest {
    @Test fun allNineteenPalettesHaveAccessibleForegroundContrast() {
        assertEquals(19,themeSeeds.size)
        for(dark in listOf(false,true)) for(index in themeSeeds.indices) {
            val scheme=fixedColorScheme(index,dark)
            listOf(scheme.onPrimary to scheme.primary, scheme.onPrimaryContainer to scheme.primaryContainer,
                scheme.onSecondary to scheme.secondary, scheme.onSecondaryContainer to scheme.secondaryContainer,
                scheme.onTertiary to scheme.tertiary, scheme.onTertiaryContainer to scheme.tertiaryContainer,
                scheme.primary to scheme.surface, scheme.onSurface to scheme.surface,
                scheme.inversePrimary to scheme.inverseSurface).forEach { (text,background) ->
                assertTrue("palette=$index dark=$dark contrast=${colorContrast(text,background)}",colorContrast(text,background)>=4.5f)
            }
            assertTrue(colorContrast(scheme.outline,scheme.surface)>=3f)
        }
    }
    @Test fun choicesAndSizesAreIndependentAndSourceDerived() {
        val comfy=readingStyles(ReadingPreset.COMFORTABLE,FontChoice.SYSTEM)
        val original=readingStyles(ReadingPreset.ORIGINAL,FontChoice.SYSTEM)
        assertEquals(14.5f,comfy.body.fontSize.value); assertEquals(15f,comfy.articleBody.fontSize.value)
        assertEquals(14f,comfy.reply.fontSize.value); assertEquals(FontWeight.Medium,comfy.title.fontWeight)
        assertEquals(16f,original.body.fontSize.value); assertEquals(FontWeight.Bold,original.title.fontWeight)
        assertEquals(appFontFamily(FontChoice.SYSTEM),comfy.body.fontFamily)
        assertEquals(appFontFamily(FontChoice.NOTO_SANS),appTypography(FontChoice.NOTO_SANS).bodyLarge.fontFamily)
        assertEquals(18,AppSettings(themeColor=18).validated().themeColor)
    }
}
