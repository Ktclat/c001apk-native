// SPDX-License-Identifier: AGPL-3.0-only
package io.github.ktclat.c001apk.nativeapp.settings

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import io.github.ktclat.c001apk.nativeapp.R

private val notoFamily = FontFamily(
    Font(R.font.noto_sans_cjk_sc_regular, FontWeight.Normal),
    Font(R.font.noto_sans_cjk_sc_medium, FontWeight.Medium),
    Font(R.font.noto_sans_cjk_sc_bold, FontWeight.Bold),
)
/** System and Material may map to the same font on stock Android; no invented distinction. */
fun appFontFamily(choice: FontChoice): FontFamily = when (choice) {
    FontChoice.SYSTEM -> FontFamily.SansSerif
    FontChoice.MATERIAL -> FontFamily.Default
    FontChoice.NOTO_SANS -> notoFamily
}
fun appTypography(choice: FontChoice): Typography {
    val family = appFontFamily(choice)
    val base = Typography()
    return Typography(
        displayLarge = base.displayLarge.copy(fontFamily=family),
        displayMedium = base.displayMedium.copy(fontFamily=family),
        displaySmall = base.displaySmall.copy(fontFamily=family),
        headlineLarge = base.headlineLarge.copy(fontFamily=family),
        headlineMedium = base.headlineMedium.copy(fontFamily=family),
        headlineSmall = base.headlineSmall.copy(fontFamily=family),
        titleLarge = base.titleLarge.copy(fontFamily=family),
        titleMedium = base.titleMedium.copy(fontFamily=family),
        titleSmall = base.titleSmall.copy(fontFamily=family),
        bodyLarge = base.bodyLarge.copy(fontFamily=family),
        bodyMedium = base.bodyMedium.copy(fontFamily=family),
        bodySmall = base.bodySmall.copy(fontFamily=family),
        labelLarge = base.labelLarge.copy(fontFamily=family),
        labelMedium = base.labelMedium.copy(fontFamily=family),
        labelSmall = base.labelSmall.copy(fontFamily=family),
    )
}
data class ReadingStyles(val body: TextStyle, val title: TextStyle, val articleBody: TextStyle, val articleTitle: TextStyle, val reply: TextStyle)
/** Source reading preset applies to post content, never compresses navigation or controls. */
fun readingStyles(preset: ReadingPreset, font: FontChoice): ReadingStyles {
    val comfortable = preset == ReadingPreset.COMFORTABLE
    val family = appFontFamily(font)
    fun body(size: Float) = TextStyle(fontFamily=family, fontSize=size.sp, lineHeight=(size * if(comfortable) 1.52f else 1.5f).sp, fontWeight=FontWeight.Normal)
    fun title(size: Float) = TextStyle(fontFamily=family, fontSize=size.sp, lineHeight=(size * if(comfortable) 1.4f else 1.5f).sp, fontWeight=if(comfortable) FontWeight.Medium else FontWeight.Bold)
    return ReadingStyles(
        body=body(if(comfortable)14.5f else 16f),
        title=title(if(comfortable)15.5f else 16f),
        articleBody=body(if(comfortable)15f else 16f),
        articleTitle=title(if(comfortable)16.5f else 17f),
        reply=body(if(comfortable)14f else 15f),
    )
}

/** For HTML TextViews only: AndroidView does not inherit Compose font families. */
fun appAndroidTypeface(context: android.content.Context, choice: FontChoice, weight: Int = 400): android.graphics.Typeface = when(choice) {
    FontChoice.SYSTEM -> android.graphics.Typeface.create("sans-serif", if(weight>=700) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
    FontChoice.MATERIAL -> if(weight>=700) android.graphics.Typeface.DEFAULT_BOLD else android.graphics.Typeface.DEFAULT
    FontChoice.NOTO_SANS -> context.resources.getFont(when { weight>=700 -> R.font.noto_sans_cjk_sc_bold; weight>=500 -> R.font.noto_sans_cjk_sc_medium; else -> R.font.noto_sans_cjk_sc_regular })
}

val LocalReadingStyles = androidx.compose.runtime.staticCompositionLocalOf { readingStyles(ReadingPreset.COMFORTABLE, FontChoice.SYSTEM) }
