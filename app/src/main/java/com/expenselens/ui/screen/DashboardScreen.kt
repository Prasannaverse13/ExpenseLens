package com.expenselens.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Camera
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.expenselens.data.db.CategoryEntity
import com.expenselens.data.db.CategoryTotal
import com.expenselens.data.db.DailyTotal
import com.expenselens.data.db.ExpenseEntity
import com.expenselens.data.repo.ExpenseRepository
import com.expenselens.ui.common.Format
import com.expenselens.ui.common.SectionTitle
import com.patrykandpatrick.vico.compose.cartesian.CartesianChartHost
import com.patrykandpatrick.vico.compose.cartesian.axis.rememberBottomAxis
import com.patrykandpatrick.vico.compose.cartesian.axis.rememberStartAxis
import com.patrykandpatrick.vico.compose.cartesian.layer.rememberColumnCartesianLayer
import com.patrykandpatrick.vico.compose.cartesian.rememberCartesianChart
import com.patrykandpatrick.vico.core.cartesian.data.CartesianChartModelProducer
import com.patrykandpatrick.vico.core.cartesian.data.columnSeries
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate
import javax.inject.Inject

data class DashboardState(
    val today: Double = 0.0,
    val week: Double = 0.0,
    val month: Double = 0.0,
    val daily: List<DailyTotal> = emptyList(),
    val categoryTotals: List<CategoryTotal> = emptyList(),
    val categories: List<CategoryEntity> = emptyList(),
    val recent: List<ExpenseEntity> = emptyList()
)

@HiltViewModel
class DashboardViewModel @Inject constructor(
    private val repo: ExpenseRepository
) : ViewModel() {

    val state: StateFlow<DashboardState> = run {
        viewModelScope.launch { repo.seedCategoriesIfEmpty() }
        val today = LocalDate.now()
        val weekStart = today.minusDays(6)
        val monthStart = today.withDayOfMonth(1)
        combine(
            repo.observeTotalForDate(today),
            combine(
                repo.observeCategoryTotals(weekStart, today),
                repo.observeCategoryTotals(monthStart, today)
            ) { w, m -> w to m },
            repo.observeDailyTotals(weekStart, today),
            repo.observeAll(),
            repo.categoryFlow()
        ) { t, weeklyAndMonthly, daily, all, cats ->
            val week = weeklyAndMonthly.first.sumOf { it.total }
            val month = weeklyAndMonthly.second.sumOf { it.total }
            DashboardState(
                today = t,
                week = week,
                month = month,
                daily = daily,
                categoryTotals = weeklyAndMonthly.second,
                categories = cats,
                recent = all.take(8)
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DashboardState())
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    onAdd: () -> Unit,
    onOpenItem: (Long) -> Unit,
    onOpenList: () -> Unit,
    onOpenSettings: () -> Unit,
    vm: DashboardViewModel = hiltViewModel()
) {
    val state by vm.state.collectAsState()
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("ExpenseLens", fontWeight = FontWeight.Bold) },
                actions = {
                    IconButton(onClick = onOpenList) {
                        Icon(Icons.Default.List, contentDescription = "List")
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings")
                    }
                }
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onAdd,
                icon = { Icon(Icons.Default.Camera, null) },
                text = { Text("Scan bill") }
            )
        }
    ) { inner ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(inner),
            contentPadding = PaddingValues(bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    SummaryCard("Today", state.today, Modifier.weight(1f))
                    SummaryCard("Week", state.week, Modifier.weight(1f))
                    SummaryCard("Month", state.month, Modifier.weight(1f))
                }
            }
            item { SectionTitle("This week") }
            item { DailyChart(state.daily) }
            item { SectionTitle("This month by category") }
            item { CategoryList(state.categoryTotals, state.categories) }
            item { SectionTitle("Recent") }
            items(state.recent, key = { it.id }) { item ->
                RecentRow(item, state.categories, onOpenItem)
            }
        }
    }
}

@Composable
private fun SummaryCard(label: String, amount: Double, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(label, style = MaterialTheme.typography.labelLarge)
            Text(
                text = Format.money(amount),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
private fun DailyChart(daily: List<DailyTotal>) {
    if (daily.isEmpty()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            Text("No data yet", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }
    val producer = remember { CartesianChartModelProducer() }
    val values = daily.map { it.total.toFloat() }
    LaunchedEffect(values) { producer.runTransaction { columnSeries { series(values) } } }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        shape = RoundedCornerShape(20.dp)
    ) {
        CartesianChartHost(
            chart = rememberCartesianChart(
                rememberColumnCartesianLayer(),
                startAxis = rememberStartAxis(),
                bottomAxis = rememberBottomAxis()
            ),
            modelProducer = producer,
            modifier = Modifier
                .fillMaxWidth()
                .height(200.dp)
                .padding(8.dp)
        )
    }
}

@Composable
private fun CategoryList(totals: List<CategoryTotal>, categories: List<CategoryEntity>) {
    if (totals.isEmpty()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Text(
                "Add a bill to see category breakdown",
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        return
    }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        shape = RoundedCornerShape(20.dp)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            val byId = categories.associateBy { it.id }
            totals.sortedByDescending { it.total }.forEach { t ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = byId[t.categoryId]?.name ?: "Other",
                        modifier = Modifier.weight(1f)
                    )
                    Text(Format.money(t.total), fontWeight = FontWeight.Medium)
                }
            }
        }
    }
}

@Composable
private fun RecentRow(
    item: ExpenseEntity,
    categories: List<CategoryEntity>,
    onClick: (Long) -> Unit
) {
    val byId = categories.associateBy { it.id }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        shape = RoundedCornerShape(16.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(item.vendor.ifBlank { "(no vendor)" }, fontWeight = FontWeight.SemiBold)
                Text(
                    "${byId[item.categoryId]?.name ?: "—"} • ${Format.date(item.billDate)}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Text(Format.money(item.totalAmount, item.currency), fontWeight = FontWeight.Bold)
            IconButton(onClick = { onClick(item.id) }) {
                Icon(Icons.Default.Edit, contentDescription = "Open")
            }
        }
    }
}
