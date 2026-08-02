package com.expenselens.ui.common

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.BlendMode
import com.expenselens.ui.theme.BlobLavender
import com.expenselens.ui.theme.BlobMint
import com.expenselens.ui.theme.BlobPeach
import com.expenselens.ui.theme.BlobSky
import com.expenselens.ui.theme.Cream50
import kotlin.math.min

/**
 * The ExpenseLens "Grainient" canvas — a cream off-white background with four
 * blurred organic blobs (peach / mint / lavender / sky) plus a fine grain
 * overlay rendered as a stippled dot field. Compose `Box` painters do not
 * natively support backdrop-blur, so we approximate the depth with
 * radial-gradient blobs and 8% alpha grain.
 */
@Composable
fun GrainientBackground(
    modifier: Modifier = Modifier,
    blobs: Boolean = true,
    grain: Boolean = true,
    content: @Composable () -> Unit
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Cream50)
    ) {
        if (blobs) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val w = size.width
                val h = size.height
                val blobSize = min(w, h) * 0.85f

                // Peach blob — top right
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(BlobPeach.copy(alpha = 0.55f), Color.Transparent),
                        center = Offset(w * 0.95f, h * 0.10f),
                        radius = blobSize * 0.55f
                    ),
                    center = Offset(w * 0.95f, h * 0.10f),
                    radius = blobSize * 0.55f
                )
                // Mint blob — bottom left
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(BlobMint.copy(alpha = 0.55f), Color.Transparent),
                        center = Offset(w * 0.05f, h * 0.90f),
                        radius = blobSize * 0.65f
                    ),
                    center = Offset(w * 0.05f, h * 0.90f),
                    radius = blobSize * 0.65f
                )
                // Lavender blob — center bottom
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(BlobLavender.copy(alpha = 0.40f), Color.Transparent),
                        center = Offset(w * 0.45f, h * 0.65f),
                        radius = blobSize * 0.55f
                    ),
                    center = Offset(w * 0.45f, h * 0.65f),
                    radius = blobSize * 0.55f
                )
                // Sky blob — center top
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(BlobSky.copy(alpha = 0.35f), Color.Transparent),
                        center = Offset(w * 0.30f, h * 0.30f),
                        radius = blobSize * 0.45f
                    ),
                    center = Offset(w * 0.30f, h * 0.30f),
                    radius = blobSize * 0.45f
                )
            }
        }

        if (grain) {
            // 8% grain — sparse black/white dots for organic film feel.
            Canvas(modifier = Modifier.fillMaxSize()) {
                val w = size.width
                val h = size.height
                val density = (w * h / 900).toInt().coerceAtMost(2000)
                val rng = java.util.Random(7)
                for (i in 0 until density) {
                    val x = rng.nextFloat() * w
                    val y = rng.nextFloat() * h
                    val r = rng.nextFloat() * 0.7f + 0.2f
                    val tint = if (rng.nextBoolean()) Color.Black else Color.White
                    drawCircle(
                        color = tint.copy(alpha = 0.08f),
                        radius = r,
                        center = Offset(x, y)
                    )
                }
            }
        }

        content()
    }
}

/**
 * Dark "ExpenseLens splash" gradient — sage to forest, top to bottom.
 */
@Composable
fun SplashBackground(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        Color(0xFF0E3B2E),   // deep emerald top
                        Color(0xFF1A4A3F),   // primary container
                        Color(0xFF2D5C4C),   // mid sage
                        Color(0xFF6F8C7A)    // bottom fades
                    )
                )
            )
    ) {
        // Subtle radial highlight
        Canvas(modifier = Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(Color.White.copy(alpha = 0.10f), Color.Transparent),
                    center = Offset(w * 0.7f, h * 0.25f),
                    radius = w * 0.6f
                ),
                center = Offset(w * 0.7f, h * 0.25f),
                radius = w * 0.6f
            )
            // Grain
            val rng = java.util.Random(11)
            for (i in 0 until 1200) {
                val x = rng.nextFloat() * w
                val y = rng.nextFloat() * h
                drawCircle(
                    color = Color.White.copy(alpha = 0.05f),
                    radius = rng.nextFloat() * 0.6f + 0.2f,
                    center = Offset(x, y)
                )
            }
        }
        content()
    }
}
