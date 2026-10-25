// SPDX-License-Identifier: AGPL-3.0-only
package io.github.ktclat.c001apk.nativeapp.settings

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import org.junit.Assert.*
import org.junit.Test

class FlutterThemeParityTest {
    @Test fun everyNativeColorRoleMatchesActualFlutterExport() {
        val fixture = requireNotNull(javaClass.classLoader!!.getResourceAsStream("theme/flutter-seed-palettes.tsv"))
            .bufferedReader().use { it.readLines() }.filterNot { it.startsWith("#") || it.isBlank() }
        assertEquals(19 * 2 * 36, fixture.size)
        val seen = mutableSetOf<Triple<Int, Boolean, String>>()
        for (line in fixture) {
            val (rawIndex, brightness, role, argb) = line.split('\t')
            assertTrue(brightness == "light" || brightness == "dark")
            val index = rawIndex.toInt()
            val dark = brightness == "dark"
            assertTrue("Duplicate fixture row: $line", seen.add(Triple(index, dark, role)))
            val actual = requireNotNull(roles(fixedColorScheme(index, dark))[role])
            assertEquals("index=$index dark=$dark role=$role", argb.toLong(16).toInt(), actual.toArgb())
        }
        for (index in themeSeeds.indices) for (dark in listOf(false, true)) {
            assertEquals(36, roles(fixedColorScheme(index, dark)).size)
            roles(fixedColorScheme(index, dark)).keys.forEach { role ->
                assertTrue("Missing role $index/$dark/$role", Triple(index, dark, role) in seen)
            }
        }
    }

    @Test fun neutralSurfacesAreSeedDerivedRatherThanFallbackPurple() {
        for (dark in listOf(false, true)) {
            val purple = fixedColorScheme(0, dark)
            val green = fixedColorScheme(9, dark)
            assertNotEquals(purple.surface, green.surface)
            assertNotEquals(purple.surfaceContainer, green.surfaceContainer)
            assertNotEquals(purple.onSurface, green.onSurface)
            assertNotEquals(purple.surfaceVariant, green.surfaceVariant)
            assertNotEquals(purple.tertiary, purple.primary)
        }
    }

    @Test fun storedSeedIndexesAndThemePreferencesAreUnchanged() {
        assertEquals(0xFF6650A4, themeSeeds[0].argb)
        assertEquals(0xFF4FAF50, themeSeeds[9].argb)
        assertEquals(0xFF8BC3A4, themeSeeds[10].argb)
        assertEquals(0xFFFF9CA8, themeSeeds[18].argb)
        assertEquals(ThemeMode.SYSTEM, AppSettings().theme)
        assertTrue(AppSettings().dynamicColor)
        assertEquals(0, AppSettings().themeColor)
        for (index in themeSeeds.indices) assertEquals(index, AppSettings(themeColor = index).validated().themeColor)
        for (index in listOf(-1, 19, Int.MAX_VALUE)) for (dark in listOf(false, true)) {
            assertThrows(IllegalArgumentException::class.java) { fixedColorScheme(index, dark) }
        }
    }

    private fun roles(c: ColorScheme): Map<String, Color> = linkedMapOf(
        "primary" to c.primary,
        "onPrimary" to c.onPrimary,
        "primaryContainer" to c.primaryContainer,
        "onPrimaryContainer" to c.onPrimaryContainer,
        "inversePrimary" to c.inversePrimary,
        "secondary" to c.secondary,
        "onSecondary" to c.onSecondary,
        "secondaryContainer" to c.secondaryContainer,
        "onSecondaryContainer" to c.onSecondaryContainer,
        "tertiary" to c.tertiary,
        "onTertiary" to c.onTertiary,
        "tertiaryContainer" to c.tertiaryContainer,
        "onTertiaryContainer" to c.onTertiaryContainer,
        "background" to c.background,
        "onBackground" to c.onBackground,
        "surface" to c.surface,
        "onSurface" to c.onSurface,
        "surfaceVariant" to c.surfaceVariant,
        "onSurfaceVariant" to c.onSurfaceVariant,
        "surfaceTint" to c.surfaceTint,
        "inverseSurface" to c.inverseSurface,
        "inverseOnSurface" to c.inverseOnSurface,
        "error" to c.error,
        "onError" to c.onError,
        "errorContainer" to c.errorContainer,
        "onErrorContainer" to c.onErrorContainer,
        "outline" to c.outline,
        "outlineVariant" to c.outlineVariant,
        "scrim" to c.scrim,
        "surfaceBright" to c.surfaceBright,
        "surfaceDim" to c.surfaceDim,
        "surfaceContainer" to c.surfaceContainer,
        "surfaceContainerHigh" to c.surfaceContainerHigh,
        "surfaceContainerHighest" to c.surfaceContainerHighest,
        "surfaceContainerLow" to c.surfaceContainerLow,
        "surfaceContainerLowest" to c.surfaceContainerLowest,
    )
}
