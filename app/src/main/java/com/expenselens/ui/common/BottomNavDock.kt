package com.expenselens.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Receipt
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.expenselens.ui.theme.Emerald800
import com.expenselens.ui.theme.Emerald500
import com.expenselens.ui.theme.GlassEdge
import com.expenselens.ui.theme.GlassLight
import com.expenselens.ui.theme.GlassStrong

data class NavTab(
    val key: String,
    val label: String,
    val icon: ImageVector
)

val DashboardTabs = listOf(
    NavTab("home", "Home", Icons.Default.Home),
    NavTab("reports", "Reports", Icons.Default.BarChart),
    NavTab("add", "Add", Icons.Default.Add),
    NavTab("expenses", "Expenses", Icons.Default.Receipt),
    NavTab("profile", "Profile", Icons.Default.Person)
)

/**
 * Floating "liquid glass" bottom navigation. 72dp tall pill, 28dp radius,
 * 24dp margin from the bottom edge. The middle "Add" tab is rendered as
 * a raised emerald circle (the FAB-style centerpiece).
 */
@Composable
fun LiquidGlassBottomNav(
    current: String,
    onSelect: (String) -> Unit,
    onAdd: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            // Lift the dock above the system navigation bar (3-button or gesture
            // pill) so the user can always reach the tabs.
            .windowInsetsPadding(WindowInsets.navigationBars)
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(72.dp)
                .shadow(
                    elevation = 24.dp,
                    shape = RoundedCornerShape(28.dp),
                    clip = false,
                    ambientColor = Emerald800.copy(alpha = 0.15f),
                    spotColor = Emerald800.copy(alpha = 0.15f)
                )
                .clip(RoundedCornerShape(28.dp))
                .background(GlassStrong)
                .border(1.dp, GlassEdge, RoundedCornerShape(28.dp))
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            DashboardTabs.forEach { tab ->
                if (tab.key == "add") {
                    // Centered raised add button
                    Box(
                        modifier = Modifier
                            .size(56.dp)
                            .shadow(
                                elevation = 12.dp,
                                shape = CircleShape,
                                clip = false,
                                ambientColor = Emerald800.copy(alpha = 0.30f),
                                spotColor = Emerald800.copy(alpha = 0.30f)
                            )
                            .clip(CircleShape)
                            .background(Brush.linearGradient(listOf(Emerald800, Emerald500)))
                            .clickable { onAdd() },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Default.Add,
                            contentDescription = "Add expense",
                            tint = Color.White
                        )
                    }
                } else {
                    val active = tab.key == current
                    Column(
                        modifier = Modifier
                            .clip(RoundedCornerShape(20.dp))
                            .clickable { onSelect(tab.key) }
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(
                            tab.icon,
                            contentDescription = tab.label,
                            tint = if (active) MaterialTheme.colorScheme.primary
                                   else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = tab.label,
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal
                            ),
                            color = if (active) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}
