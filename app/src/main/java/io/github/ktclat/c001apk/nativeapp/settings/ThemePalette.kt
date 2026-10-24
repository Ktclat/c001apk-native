// SPDX-License-Identifier: AGPL-3.0-only
package io.github.ktclat.c001apk.nativeapp.settings

import androidx.compose.ui.graphics.luminance

/** Stable indexes and seeds from Flutter constants/constants.dart. */
data class ThemeSeed(val label: String, val argb: Long)
val themeSeeds = listOf(
    ThemeSeed("默认",0xFF6650A4), ThemeSeed("红色",0xFFF44336), ThemeSeed("粉色",0xFFE91E63),
    ThemeSeed("紫色",0xFF9C27B0), ThemeSeed("靛蓝",0xFF3F51B5), ThemeSeed("蓝色",0xFF2196F3),
    ThemeSeed("浅蓝",0xFF03A9F4), ThemeSeed("青色",0xFF00BCD4), ThemeSeed("蓝绿",0xFF009688),
    ThemeSeed("绿色",0xFF4FAF50), ThemeSeed("浅绿",0xFF8BC3A4), ThemeSeed("青柠",0xFFCDDC39),
    ThemeSeed("黄色",0xFFFFEB3B), ThemeSeed("琥珀",0xFFFFC107), ThemeSeed("橙色",0xFFFF9800),
    ThemeSeed("深橙",0xFFFF5722), ThemeSeed("棕色",0xFF795548), ThemeSeed("蓝灰",0xFF607D8F),
    ThemeSeed("樱粉",0xFFFF9CA8),
)

/** Native contrast-safe tonal mapping, not Flutter's HCT tonal-generation algorithm. */
fun fixedColorScheme(index: Int, dark: Boolean): androidx.compose.material3.ColorScheme {
    require(index in themeSeeds.indices)
    val base = if (dark) androidx.compose.material3.darkColorScheme() else androidx.compose.material3.lightColorScheme()
    val seed = androidx.compose.ui.graphics.Color(themeSeeds[index].argb)
    val primary = contrastColor(seed, base.surface, 4.8f)
    val container = androidx.compose.ui.graphics.lerp(seed, base.surface, 0.84f)
    val secondarySeed = androidx.compose.ui.graphics.lerp(seed, androidx.compose.ui.graphics.Color.Gray, 0.45f)
    val secondary = contrastColor(secondarySeed, base.surface, 4.8f)
    val secondaryContainer = androidx.compose.ui.graphics.lerp(secondarySeed, base.surface, 0.84f)
    return base.copy(
        primary=primary, onPrimary=contrastForeground(primary),
        primaryContainer=container, onPrimaryContainer=contrastColor(seed,container,7f),
        secondary=secondary, onSecondary=contrastForeground(secondary),
        secondaryContainer=secondaryContainer, onSecondaryContainer=contrastColor(secondarySeed,secondaryContainer,7f),
        tertiary=primary, onTertiary=contrastForeground(primary),
        tertiaryContainer=container, onTertiaryContainer=contrastColor(seed,container,7f),
        inversePrimary=contrastColor(seed,base.inverseSurface,4.8f),
        outline=contrastColor(secondarySeed,base.surface,3.2f),
        surfaceTint=primary,
    )
}
fun colorContrast(foreground: androidx.compose.ui.graphics.Color, background: androidx.compose.ui.graphics.Color): Float {
    val a = foreground.luminance(); val b = background.luminance()
    return (maxOf(a,b) + 0.05f) / (minOf(a,b) + 0.05f)
}
private fun contrastForeground(background: androidx.compose.ui.graphics.Color): androidx.compose.ui.graphics.Color =
    if (colorContrast(androidx.compose.ui.graphics.Color.Black,background) >= colorContrast(androidx.compose.ui.graphics.Color.White,background)) androidx.compose.ui.graphics.Color.Black else androidx.compose.ui.graphics.Color.White
private fun contrastColor(seed: androidx.compose.ui.graphics.Color, background: androidx.compose.ui.graphics.Color, minimum: Float): androidx.compose.ui.graphics.Color {
    if (colorContrast(seed,background) >= minimum) return seed
    val end = contrastForeground(background)
    for (step in 1..100) {
        val candidate=androidx.compose.ui.graphics.lerp(seed,end,step/100f)
        if (colorContrast(candidate,background) >= minimum) return candidate
    }
    return end
}
