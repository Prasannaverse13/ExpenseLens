package com.expenselens.ui.screen

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.TrendingDown
import androidx.compose.material.icons.automirrored.filled.TrendingFlat
import androidx.compose.material.icons.automirrored.filled.TrendingUp
import androidx.compose.material.icons.filled.Event
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material.icons.filled.Park
import androidx.compose.material.icons.filled.PauseCircle
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material.icons.filled.Store
import androidx.compose.material.icons.filled.Today
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.expenselens.data.insights.SpendingInsight
import com.expenselens.ui.theme.Amber400
import com.expenselens.ui.theme.Cream400
import com.expenselens.ui.theme.Emerald400
import com.expenselens.ui.theme.Emerald500
import com.expenselens.ui.theme.Emerald700
import com.expenselens.ui.theme.GlassInner
import com.expenselens.ui.theme.GlassLight
import com.expenselens.ui.theme.Sage200

/**
 * Horizontal carousel of "Spending Insight" cards. Shown on the
 * Dashboard between the Month-Total card and the Action tiles.
 *
 *  - Pure Compose: takes a List<SpendingInsight> and renders it.
 *  - Empty list: shows nothing (no card, no header). The dashboard
 *    composable just won't render the section.
 *  - Premium gating: the caller can pass `isPremium=true` to hide
 *    cards whose `requiresPremium` flag is true.
 *  - 240dp wide per card, edge-to-edge scroll with a right-edge
 *    fade hint to signal "more →".
 *
 *  v2: handles VendorMonthOverMonth, UpcomingRecurring, and
 *  UnusedRecurring variants. Each gets its own icon + accent
 *  color so the user can distinguish insight types at a glance.
 */
@Composable
fun InsightsCarousel(
    insights: List<SpendingInsight>,
    isPremium: Boolean = true,
    modifier: Modifier = Modifier
) {
    val visible = if (isPremium) insights else insights.filter { !it.requiresPremium }
    if (visible.isEmpty()) return

    val listState = rememberLazyListState()

    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Spending Insights",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = "This month",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.height(12.dp))

        Box(modifier = Modifier.fillMaxWidth()) {
            LazyRow(
                state = listState,
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(visible, key = { insightKey(it) }) { insight ->
                    InsightCard(insight = insight)
                }
            }

            if (listState.canScrollForward) {
                Box(
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .width(48.dp)
                        .height(120.dp)
                        .background(
                            Brush.horizontalGradient(
                                colors = listOf(Color.Transparent, Cream400.copy(alpha = 0.6f))
                            )
                        )
                )
            }
        }
    }
}

/**
 * Stable key per insight. The carousel uses these to detect when
 * a card's data has changed (and re-trigger animations).
 */
private fun insightKey(insight: SpendingInsight): String = when (insight) {
    is SpendingInsight.MonthOverMonth -> "mom-${insight.thisTotal.toInt()}"
    is SpendingInsight.TopCategory -> "top-${insight.categoryName}-${insight.amount.toInt()}"
    is SpendingInsight.VendorMonthOverMonth -> "ven-${insight.vendor}-${insight.pctDelta.toInt()}"
    is SpendingInsight.UpcomingRecurring -> "up-${insight.vendor}-${insight.daysUntil}"
    is SpendingInsight.DailyAverage -> "avg-${insight.thisAvg.toInt()}"
    is SpendingInsight.HighestDay -> "hi-${insight.date}-${insight.amount.toInt()}"
    is SpendingInsight.QuietestDay -> "quiet-${insight.dayName}"
    is SpendingInsight.UnusedRecurring -> "unused-${insight.vendorCount}-${insight.totalMonthlyAmount.toInt()}"
}

/**
 * One insight card. Fixed size (240w × 120h) so the carousel layout
 * is stable even with empty copy. 44dp+ touch target via the
 * surrounding clickable — useful when a future "drill-down" wires
 * the card to a destination.
 */
@Composable
private fun InsightCard(insight: SpendingInsight) {
    val (icon, accent) = visualsFor(insight)
    val delta = deltaFor(insight)
    Box(
        modifier = Modifier
            .width(240.dp)
            .height(120.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(GlassLight)
            .border(1.dp, GlassInner, RoundedCornerShape(20.dp))
            .clickable { /* TODO: navigate to /insights?type=… */ }
            .padding(horizontal = 16.dp, vertical = 14.dp)
    ) {
        // Top row: icon + (optional) trend badge
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(accent.copy(alpha = 0.18f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = accent,
                    modifier = Modifier.size(20.dp)
                )
            }
            if (delta != null) {
                Spacer(Modifier.width(8.dp))
                TrendBadge(delta = delta, accent = accent)
            }
        }

        Spacer(Modifier.height(10.dp))

        Text(
            text = insight.title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = insight.subtitle,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/** A small colored pill: +12%, −4%, ~. Visual sugar. */
@Composable
private fun TrendBadge(delta: TrendDelta, accent: Color) {
    val (icon, label) = when (delta) {
        is TrendDelta.Up -> Icons.AutoMirrored.Filled.TrendingUp to "+${delta.pct.toInt()}%"
        is TrendDelta.Down -> Icons.AutoMirrored.Filled.TrendingDown to "${delta.pct.toInt()}%"
        is TrendDelta.Flat -> Icons.AutoMirrored.Filled.TrendingFlat to "Same"
    }
    val textColor = if (delta is TrendDelta.Down) Emerald700 else accent
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .clip(CircleShape)
            .background(accent.copy(alpha = 0.16f))
            .padding(horizontal = 8.dp, vertical = 3.dp)
    ) {
        AnimatedVisibility(visible = true, enter = fadeIn(), exit = fadeOut()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = textColor,
                    modifier = Modifier.size(12.dp)
                )
                Spacer(Modifier.width(3.dp))
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelSmall,
                    color = textColor,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
}

private sealed class TrendDelta {
    data class Up(val pct: Double) : TrendDelta()
    data class Down(val pct: Double) : TrendDelta()
    data object Flat : TrendDelta()
}

/**
 * Maps each insight to its trend badge. Only insights with a
 * meaningful directional change show a badge; pure informational
 * ones (Upcoming, Unused) don't.
 */
private fun deltaFor(insight: SpendingInsight): TrendDelta? = when (insight) {
    is SpendingInsight.MonthOverMonth -> when {
        insight.pctDelta > 5.0 -> TrendDelta.Up(insight.pctDelta)
        insight.pctDelta < -5.0 -> TrendDelta.Down(insight.pctDelta)
        else -> TrendDelta.Flat
    }
    is SpendingInsight.TopCategory -> insight.pctDelta?.let { pct ->
        when {
            pct > 5.0 -> TrendDelta.Up(pct)
            pct < -5.0 -> TrendDelta.Down(pct)
            else -> TrendDelta.Flat
        }
    }
    is SpendingInsight.VendorMonthOverMonth -> when {
        insight.pctDelta > 5.0 -> TrendDelta.Up(insight.pctDelta)
        insight.pctDelta < -5.0 -> TrendDelta.Down(insight.pctDelta)
        else -> TrendDelta.Flat
    }
    else -> null
}

/**
 * Per-insight icon + accent color. Mirrors the rule of thumb used
 * in the rest of the app:
 *   - spending up (warning) → Amber400
 *   - spending down (savings good) → Emerald500
 *   - neutral / informational → Sage200 or Emerald400
 *   - actionable / due-soon → Amber400
 *
 * v2 picks:
 *   - VendorMonthOverMonth  → Store + (up=Amber, down=Emerald)
 *   - UpcomingRecurring     → Event + Amber (actionable)
 *   - UnusedRecurring       → PauseCircle + Sage (informational)
 */
private fun visualsFor(insight: SpendingInsight): Pair<ImageVector, Color> = when (insight) {
    is SpendingInsight.MonthOverMonth -> when {
        insight.pctDelta > 5.0 -> Icons.AutoMirrored.Filled.TrendingUp to Amber400
        insight.pctDelta < -5.0 -> Icons.AutoMirrored.Filled.TrendingDown to Emerald500
        else -> Icons.AutoMirrored.Filled.TrendingFlat to Sage200
    }
    is SpendingInsight.TopCategory -> Icons.Default.Restaurant to Emerald700
    is SpendingInsight.VendorMonthOverMonth -> when {
        insight.pctDelta > 0 -> Icons.Default.Store to Amber400
        else -> Icons.Default.Store to Emerald500
    }
    is SpendingInsight.UpcomingRecurring -> Icons.Default.Event to Amber400
    is SpendingInsight.DailyAverage -> Icons.Default.Today to Sage200
    is SpendingInsight.HighestDay -> Icons.Default.LocalFireDepartment to Amber400
    is SpendingInsight.QuietestDay -> Icons.Default.Park to Emerald400
    is SpendingInsight.UnusedRecurring -> Icons.Default.PauseCircle to Sage200
}
