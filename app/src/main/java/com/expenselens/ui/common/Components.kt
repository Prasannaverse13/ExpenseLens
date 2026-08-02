package com.expenselens.ui.common

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.expenselens.domain.model.CategoryType
import com.expenselens.ui.theme.Emerald800
import com.expenselens.ui.theme.Emerald500
import com.expenselens.ui.theme.GlassEdge
import com.expenselens.ui.theme.GlassInner
import com.expenselens.ui.theme.GlassLight
import com.expenselens.ui.theme.GlassStrong
import com.expenselens.ui.theme.OnEmerald

/**
 * ExpenseLens primary button: 60dp tall, 135deg gradient #1A4A3F -> #6FA593,
 * white text, oversized radius.
 */
@Composable
fun ExpenseLensPrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    trailing: (@Composable () -> Unit)? = null
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(20.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = Color.Transparent,
            contentColor = OnEmerald,
            disabledContainerColor = Color.Transparent,
            disabledContentColor = OnEmerald.copy(alpha = 0.5f)
        ),
        contentPadding = PaddingValues(horizontal = 24.dp, vertical = 16.dp),
        modifier = modifier
            .height(60.dp)
            .fillMaxWidth()
            .shadow(
                elevation = 16.dp,
                shape = RoundedCornerShape(20.dp),
                clip = false,
                ambientColor = Emerald800.copy(alpha = 0.20f),
                spotColor = Emerald800.copy(alpha = 0.20f)
            )
            .clip(RoundedCornerShape(20.dp))
            .background(
                Brush.linearGradient(
                    colors = listOf(Emerald800, Emerald500)
                )
            )
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Text(
                text = text,
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                color = OnEmerald
            )
            if (trailing != null) {
                Spacer(Modifier.width(8.dp))
                trailing()
            }
        }
    }
}

/**
 * ExpenseLens glass card: 30% white surface, 1px white inner border, soft
 * tinted shadow. 28dp radius for major, 20dp for secondary.
 *
 * The content slot is a vertical Column so child composables stack from
 * top to bottom. If a row is required, wrap your content in a Row.
 */
@Composable
fun ExpenseLensGlassCard(
    modifier: Modifier = Modifier,
    radius: Dp = 28.dp,
    contentPadding: PaddingValues = PaddingValues(20.dp),
    verticalArrangement: Arrangement.Vertical = Arrangement.spacedBy(8.dp),
    content: @Composable ColumnScope.() -> Unit
) {
    Box(
        modifier = modifier
            .shadow(
                elevation = 18.dp,
                shape = RoundedCornerShape(radius),
                clip = false,
                ambientColor = Emerald800.copy(alpha = 0.08f),
                spotColor = Emerald800.copy(alpha = 0.08f)
            )
            .clip(RoundedCornerShape(radius))
            .background(GlassLight)
            .border(BorderStroke(1.dp, GlassEdge), RoundedCornerShape(radius))
    ) {
        Column(Modifier.padding(contentPadding), verticalArrangement = verticalArrangement) {
            content()
        }
    }
}

@Composable
fun ExpenseLensSecondaryCard(
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(16.dp),
    content: @Composable () -> Unit
) = ExpenseLensGlassCard(modifier, radius = 20.dp, contentPadding = contentPadding) { content() }

/**
 * Common top bar used by all secondary screens (Capture, Reports, Profile,
 * Expenses, ManualEntry, Detail, Settings, List). Reserves space for the
 * system status bar so the back-arrow + title aren't hidden behind it.
 */
@Composable
fun ExpenseLensTopBar(
    title: String,
    onBack: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.statusBars)
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (onBack != null) {
            IconButton(onClick = onBack) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                    tint = MaterialTheme.colorScheme.onSurface
                )
            }
        } else {
            Spacer(Modifier.width(8.dp))
        }
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier
                .weight(1f)
                .padding(start = if (onBack != null) 0.dp else 4.dp)
        )
        if (trailing != null) trailing()
    }
}
@Composable
fun ExpenseLensActionTile(
    label: String,
    icon: (@Composable () -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(20.dp))
            .background(GlassLight)
            .border(BorderStroke(1.dp, GlassInner), RoundedCornerShape(20.dp))
            .padding(vertical = 16.dp, horizontal = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        if (icon != null) icon()
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

/** A circle used for the dashboard action icons (e.g. QR for Scan). */
@Composable
fun IconBubble(
    size: Dp = 40.dp,
    tint: Color = MaterialTheme.colorScheme.onSurface,
    content: @Composable () -> Unit
) {
    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surface),
        contentAlignment = Alignment.Center
    ) { content() }
}

@Composable
fun LoadingBox() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) { CircularProgressIndicator() }
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
        modifier = modifier.padding(horizontal = 16.dp, vertical = 8.dp)
    )
}

@Composable
fun EmptyState(message: String) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun CategoryChip(label: String) {
    val color = when (CategoryType.fromName(label)) {
        CategoryType.FOOD_COST -> MaterialTheme.colorScheme.tertiaryContainer
        CategoryType.PACKAGING_COST -> MaterialTheme.colorScheme.secondaryContainer
        CategoryType.ELECTRICITY -> MaterialTheme.colorScheme.errorContainer
        CategoryType.STAFF_SALARY -> MaterialTheme.colorScheme.primaryContainer
        CategoryType.STAFF_RENT -> MaterialTheme.colorScheme.surfaceVariant
        CategoryType.SHOP_RENT -> MaterialTheme.colorScheme.surfaceVariant
        CategoryType.RENT -> MaterialTheme.colorScheme.surfaceVariant
        CategoryType.MAINTENANCE -> MaterialTheme.colorScheme.surfaceVariant
        CategoryType.MISCELLANEOUS -> MaterialTheme.colorScheme.surfaceVariant
        CategoryType.UNKNOWN -> MaterialTheme.colorScheme.surfaceVariant
    }
    Card(modifier = Modifier.padding(2.dp)) {
        Text(
            text = label,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            style = MaterialTheme.typography.labelMedium
        )
        @Suppress("UNUSED_EXPRESSION") color
    }
}

@Composable
fun <T> VerticalList(
    items: List<T>,
    key: (T) -> Any,
    contentPadding: PaddingValues = PaddingValues(16.dp),
    verticalArrangement: Arrangement.Vertical = Arrangement.spacedBy(8.dp),
    itemContent: @Composable (T) -> Unit
) {
    LazyColumn(
        contentPadding = contentPadding,
        verticalArrangement = verticalArrangement,
        modifier = Modifier.fillMaxSize()
    ) {
        items(items, key = key) { item ->
            itemContent(item)
        }
        item { Spacer(modifier = Modifier.height(48.dp)) }
    }
}

@Composable
fun HeaderBlock(title: String, subtitle: String? = null) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp)
    ) {
        Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        if (subtitle != null) {
            Text(
                subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
