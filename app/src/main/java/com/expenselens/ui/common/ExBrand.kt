package com.expenselens.ui.common

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.expenselens.ui.theme.GlassEdge
import com.expenselens.ui.theme.GlassLight
import com.expenselens.ui.theme.Sage200

/**
 * The "EX" brand mark — a circular plate with a serif/monogram wordmark.
 * Used on the splash and in the top app bars.
 */
@Composable
fun ExBrandMark(
    size: Dp = 56.dp,
    textColor: Color = Color(0xFF1C1C19),
    plateColor: Color = Sage200,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(plateColor)
            .border(2.dp, GlassEdge, CircleShape),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = "EX",
            color = textColor,
            fontSize = (size.value * 0.45f).sp,
            fontWeight = FontWeight.Black,
            textAlign = TextAlign.Center,
            letterSpacing = (-1).sp
        )
    }
}

/**
 * 80px avatar with white border + soft shadow. Used on the dashboard.
 */
@Composable
fun BrandAvatar(
    size: Dp = 48.dp,
    initials: String = "A",
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(Brush.linearGradient(listOf(Color(0xFFE8C9A0), Color(0xFFC9A87A))))
            .border(2.dp, Color.White, CircleShape),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = initials,
            color = Color(0xFF1C1C19),
            fontSize = (size.value * 0.40f).sp,
            fontWeight = FontWeight.Bold
        )
    }
}
