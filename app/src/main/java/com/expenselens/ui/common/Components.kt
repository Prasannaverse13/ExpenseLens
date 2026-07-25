package com.expenselens.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.expenselens.domain.model.CategoryType

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
