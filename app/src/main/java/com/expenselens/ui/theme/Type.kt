package com.expenselens.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// ExpenseLens uses Inter (system SansSerif on Android as a high-quality
// fallback). Headlines use tight negative letter-spacing for impact;
// body text uses a relaxed 0.2px tracking at 1.5x line-height.
val SerifFamily = FontFamily.Serif        // for splash / brand moment
val SansFamily  = FontFamily.SansSerif    // == Inter substitute

val ExpenseLensTypography = Typography(
    // display + headline — display size, bold weight, -1px tracking
    displayLarge   = TextStyle(fontFamily = SansFamily, fontWeight = FontWeight.Bold,     fontSize = 40.sp, lineHeight = 48.sp, letterSpacing = (-1.0).sp),
    displayMedium  = TextStyle(fontFamily = SansFamily, fontWeight = FontWeight.Bold,     fontSize = 36.sp, lineHeight = 44.sp, letterSpacing = (-0.8).sp),
    displaySmall   = TextStyle(fontFamily = SansFamily, fontWeight = FontWeight.Bold,     fontSize = 32.sp, lineHeight = 40.sp, letterSpacing = (-1.0).sp),

    // headlines
    headlineLarge  = TextStyle(fontFamily = SansFamily, fontWeight = FontWeight.Bold,     fontSize = 32.sp, lineHeight = 40.sp, letterSpacing = (-1.0).sp),
    headlineMedium = TextStyle(fontFamily = SansFamily, fontWeight = FontWeight.Bold,     fontSize = 28.sp, lineHeight = 34.sp, letterSpacing = (-0.8).sp),
    headlineSmall  = TextStyle(fontFamily = SansFamily, fontWeight = FontWeight.SemiBold, fontSize = 24.sp, lineHeight = 30.sp, letterSpacing = (-0.5).sp),

    // titles
    titleLarge     = TextStyle(fontFamily = SansFamily, fontWeight = FontWeight.SemiBold, fontSize = 20.sp, lineHeight = 30.sp, letterSpacing = (-0.5).sp),
    titleMedium    = TextStyle(fontFamily = SansFamily, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 24.sp, letterSpacing = 0.0.sp),
    titleSmall     = TextStyle(fontFamily = SansFamily, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, lineHeight = 20.sp, letterSpacing = 0.0.sp),

    // body
    bodyLarge      = TextStyle(fontFamily = SansFamily, fontWeight = FontWeight.Normal,   fontSize = 16.sp, lineHeight = 24.sp, letterSpacing = 0.2.sp),
    bodyMedium     = TextStyle(fontFamily = SansFamily, fontWeight = FontWeight.Normal,   fontSize = 14.sp, lineHeight = 21.sp, letterSpacing = 0.2.sp),
    bodySmall      = TextStyle(fontFamily = SansFamily, fontWeight = FontWeight.Normal,   fontSize = 12.sp, lineHeight = 18.sp, letterSpacing = 0.2.sp),

    // labels
    labelLarge     = TextStyle(fontFamily = SansFamily, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, lineHeight = 20.sp, letterSpacing = 0.5.sp),
    labelMedium    = TextStyle(fontFamily = SansFamily, fontWeight = FontWeight.Medium,   fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 0.5.sp),
    labelSmall     = TextStyle(fontFamily = SansFamily, fontWeight = FontWeight.SemiBold, fontSize = 11.sp, lineHeight = 14.sp, letterSpacing = 0.6.sp),
)
