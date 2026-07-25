package com.expenselens.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.expenselens.data.db.CategoryEntity
import com.expenselens.data.db.ExpenseEntity
import com.expenselens.data.repo.ExpenseRepository
import com.expenselens.ui.common.Format
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import java.time.LocalDate
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ListScreen(
    onOpen: (Long) -> Unit,
    onBack: () -> Unit,
    vm: ListViewModel = hiltViewModel()
) {
    val results by vm.results.collectAsState()
    val filters by vm.filters.collectAsState()
    val categories by vm.categories.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Expenses") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, null) }
                }
            )
        }
    ) { inner ->
        Column(modifier = Modifier
            .fillMaxSize()
            .padding(inner)) {
            OutlinedTextField(
                value = filters.query,
                onValueChange = vm::setQuery,
                leadingIcon = { Icon(Icons.Default.Search, null) },
                placeholder = { Text("Search vendor, bill no., notes") },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                singleLine = true
            )
            CategoryFilters(categories, filters.categoryId, vm::setCategory)
            LazyColumn(
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    horizontal = 16.dp, vertical = 8.dp
                ),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(results, key = { it.id }) { e ->
                    Row(e, categories, onOpen)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CategoryFilters(
    categories: List<CategoryEntity>,
    selected: Long?,
    onSelect: (Long?) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        FilterChip(
            selected = selected == null,
            onClick = { onSelect(null) },
            label = { Text("All") }
        )
        categories.forEach { c ->
            FilterChip(
                selected = c.id == selected,
                onClick = { onSelect(c.id) },
                label = { Text(c.name) }
            )
        }
    }
}

@Composable
private fun Row(e: ExpenseEntity, categories: List<CategoryEntity>, onOpen: (Long) -> Unit) {
    val byId = categories.associateBy { it.id }
    Card(
        modifier = Modifier
            .fillMaxWidth(),
        shape = RoundedCornerShape(14.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(e.vendor.ifBlank { "(no vendor)" }, fontWeight = FontWeight.SemiBold)
                Text(
                    "${byId[e.categoryId]?.name ?: "—"} • ${Format.date(e.billDate)}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Text(Format.money(e.totalAmount, e.currency), fontWeight = FontWeight.Bold)
            IconButton(onClick = { onOpen(e.id) }) { Icon(Icons.Default.Search, null) }
        }
    }
}
