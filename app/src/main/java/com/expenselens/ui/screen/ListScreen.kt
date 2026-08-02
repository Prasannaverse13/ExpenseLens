package com.expenselens.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.expenselens.data.db.CategoryEntity
import com.expenselens.data.db.ExpenseEntity
import com.expenselens.data.repo.ExpenseRepository
import com.expenselens.ui.common.ExpenseLensSecondaryCard
import com.expenselens.ui.common.ExpenseLensTopBar
import com.expenselens.ui.common.Format
import com.expenselens.ui.common.LiquidGlassBottomNav
import com.expenselens.ui.theme.GlassEdge
import com.expenselens.ui.theme.GlassInner
import com.expenselens.ui.theme.GlassLight
import com.expenselens.ui.common.GrainientBackground
import com.expenselens.ui.theme.Sage200
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

data class ListFilters(
    val query: String = "",
    val categoryId: Long? = null
)

@HiltViewModel
class ListViewModel @Inject constructor(
    private val repo: ExpenseRepository
) : ViewModel() {

    private val _filters = MutableStateFlow(ListFilters())
    val filters: StateFlow<ListFilters> = _filters.asStateFlow()

    val categories = repo.categoryFlow()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val results: StateFlow<List<ExpenseEntity>> = combine(
        _filters, repo.observeAll()
    ) { f, all ->
        if (f.query.isBlank() && f.categoryId == null) all
        else {
            val q = f.query.lowercase()
            all.filter { e ->
                val byText = q.isBlank() ||
                    e.vendor.lowercase().contains(q) ||
                    (e.billNumber?.lowercase()?.contains(q) == true) ||
                    (e.notes?.lowercase()?.contains(q) == true)
                val byCat = f.categoryId == null || e.categoryId == f.categoryId
                byText && byCat
            }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun setQuery(q: String) { _filters.value = _filters.value.copy(query = q) }
    fun setCategory(id: Long?) { _filters.value = _filters.value.copy(categoryId = id) }
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun ListScreen(
    onOpen: (Long) -> Unit,
    onBack: () -> Unit,
    onAdd: () -> Unit = {},
    onTab: (String) -> Unit = {},
    vm: ListViewModel = hiltViewModel()
) {
    val results by vm.results.collectAsState()
    val filters by vm.filters.collectAsState()
    val categories by vm.categories.collectAsState()

    GrainientBackground {
        Box(modifier = Modifier.fillMaxSize()) {
            Column(modifier = Modifier.fillMaxSize()) {
                ExpenseLensTopBar(title = "Expenses", onBack = onBack)

                // Search
                OutlinedTextField(
                    value = filters.query,
                    onValueChange = vm::setQuery,
                    placeholder = { Text("Search vendor, bill no., notes") },
                    leadingIcon = { Icon(Icons.Default.Search, null) },
                    singleLine = true,
                    shape = RoundedCornerShape(16.dp),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = GlassLight,
                        unfocusedContainerColor = GlassLight,
                        focusedBorderColor = GlassEdge,
                        unfocusedBorderColor = GlassInner
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp, vertical = 8.dp)
                )

                // Category chips — wrap to multiple lines so all 8 fit
                // without horizontal scrolling.
                @OptIn(ExperimentalLayoutApi::class)
                FlowRow(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    CategoryChipButton(
                        label = "All",
                        selected = filters.categoryId == null,
                        onClick = { vm.setCategory(null) }
                    )
                    categories.forEach { c ->
                        CategoryChipButton(
                            label = c.name,
                            selected = filters.categoryId == c.id,
                            onClick = { vm.setCategory(c.id) }
                        )
                    }
                }

                // List
                LazyColumn(
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                        start = 24.dp, end = 24.dp, top = 8.dp, bottom = 120.dp
                    ),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    if (results.isEmpty()) {
                        item {
                            ExpenseLensSecondaryCard {
                                Text(
                                    "No expenses match your filters.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    } else {
                        items(results, key = { it.id }) { e ->
                            ExpenseListRow(e, categories, onOpen)
                        }
                    }
                }
            }
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
            ) {
                LiquidGlassBottomNav(
                    current = "expenses",
                    onSelect = onTab,
                    onAdd = onAdd
                )
            }
        }
    }
}

@Composable
private fun CategoryChipButton(label: String, selected: Boolean, onClick: () -> Unit) {
    val bg = if (selected) Sage200.copy(alpha = 0.7f) else GlassLight
    val border = if (selected) MaterialTheme.colorScheme.primary else GlassInner
    val textColor = if (selected) MaterialTheme.colorScheme.onSurface
                    else MaterialTheme.colorScheme.onSurfaceVariant
    Text(
        text = label,
        style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
        color = textColor,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(bg)
            .border(1.dp, border, RoundedCornerShape(50))
            .clickable { onClick() }
            .padding(horizontal = 16.dp, vertical = 8.dp)
    )
}

@Composable
private fun ExpenseListRow(
    e: ExpenseEntity,
    categories: List<CategoryEntity>,
    onOpen: (Long) -> Unit
) {
    val byId = categories.associateBy { it.id }
    ExpenseLensSecondaryCard(
        modifier = Modifier.clickable { onOpen(e.id) },
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(Sage200.copy(alpha = 0.5f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Default.CameraAlt,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(20.dp)
                )
            }
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 12.dp)
            ) {
                Text(
                    e.vendor.ifBlank { "(no vendor)" },
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    "${byId[e.categoryId]?.name ?: "—"} • ${Format.date(e.billDate)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Text(
                Format.money(e.totalAmount, e.currency),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.Bold
            )
        }
    }
}
