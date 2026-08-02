package com.expenselens.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp

// ExpenseLens shape system: oversized radii for major surfaces, smaller
// for inputs, full-circle for avatars.
val ExpenseLensShapes = Shapes(
    extraSmall = RoundedCornerShape(12.dp),
    small      = RoundedCornerShape(16.dp),  // inputs
    medium     = RoundedCornerShape(20.dp),  // secondary cards
    large      = RoundedCornerShape(28.dp),  // major cards + bottom nav
    extraLarge = RoundedCornerShape(36.dp),
)

private val ExpenseLensLight = lightColorScheme(
    primary = Emerald900,
    onPrimary = OnEmerald,
    primaryContainer = Emerald100,
    onPrimaryContainer = Emerald700,
    inversePrimary = Emerald300,

    secondary = Amber900,
    onSecondary = OnEmerald,
    secondaryContainer = Amber400,
    onSecondaryContainer = Amber800,

    tertiary = Sage900,
    onTertiary = OnEmerald,
    tertiaryContainer = Sage100,
    onTertiaryContainer = Sage800,

    error = ErrorRed,
    onError = OnError,
    errorContainer = ErrorContainer,
    onErrorContainer = OnErrorContainer,

    background = Cream50,
    onBackground = Onyx,
    surface = Cream50,
    onSurface = Onyx,
    surfaceVariant = Cream400,
    onSurfaceVariant = OnyxSoft,
    surfaceTint = Emerald600,
    inverseSurface = InverseSurface,
    inverseOnSurface = InverseOnSurface,

    outline = OnyxDim,
    outlineVariant = OnyxLine,
)

private val ExpenseLensDark = darkColorScheme(
    primary = Emerald300,
    onPrimary = Emerald900,
    primaryContainer = Emerald800,
    onPrimaryContainer = Emerald400,
    inversePrimary = Emerald900,

    secondary = Amber500,
    onSecondary = Onyx,
    secondaryContainer = Amber800,
    onSecondaryContainer = Amber300,

    tertiary = Sage200,
    onTertiary = Sage900,
    tertiaryContainer = Sage700,
    onTertiaryContainer = Sage400,

    error = ErrorRed,
    onError = OnError,
    errorContainer = OnErrorContainer,
    onErrorContainer = ErrorContainer,

    background = Onyx,
    onBackground = Cream50,
    surface = Onyx,
    onSurface = Cream50,
    surfaceVariant = InverseSurface,
    onSurfaceVariant = Cream300,
    surfaceTint = Emerald400,
    inverseSurface = Cream50,
    inverseOnSurface = Onyx,

    outline = OnyxLine,
    outlineVariant = OnyxDim,
)

@Composable
fun ExpenseLensTheme(
    useDark: Boolean = false,
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = if (useDark) ExpenseLensDark else ExpenseLensLight,
        typography = ExpenseLensTypography,
        shapes = ExpenseLensShapes,
        content = content
    )
}
