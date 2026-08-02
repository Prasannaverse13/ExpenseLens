package com.expenselens.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.TableChart
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.expenselens.data.db.CategoryEntity
import com.expenselens.data.db.CategoryTotal
import com.expenselens.data.repo.ExpenseRepository
import com.expenselens.domain.model.Expense
import com.expenselens.export.ExportService
import com.expenselens.ui.common.ExpenseLensSecondaryCard
import com.expenselens.ui.common.ExpenseLensTopBar
import com.expenselens.ui.common.Format
import com.expenselens.ui.common.GrainientBackground
import com.expenselens.ui.common.LiquidGlassBottomNav
import com.expenselens.ui.common.shareFile
import com.expenselens.ui.theme.GlassEdge
import com.expenselens.ui.theme.GlassInner
import com.expenselens.ui.theme.GlassLight
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDate
import javax.inject.Inject

data class ReportsState(
    val monthTotal: Double = 0.0,
    val weekTotal: Double = 0.0,
    val byCategory: List<CategoryTotal> = emptyList(),
    val categories: List<CategoryEntity> = emptyList()
)

@HiltViewModel
class ReportsViewModel @Inject constructor(
    private val repo: ExpenseRepository,
    private val exports: ExportService
) : ViewModel() {
    val state: StateFlow<ReportsState> = run {
        val today = LocalDate.now()
        val monthStart = today.withDayOfMonth(1)
        val weekStart = today.minusDays(6)
        combine(
            repo.observeCategoryTotals(monthStart, today),
            repo.observeCategoryTotals(weekStart, today),
            repo.observeAll(),
            repo.categoryFlow()
        ) { month, week, _, cats ->
            ReportsState(
                monthTotal = month.sumOf { it.total },
                weekTotal = week.sumOf { it.total },
                byCategory = month,
                categories = cats
            )
        }.stateIn(viewModelScope, SharingStarted.Eagerly, ReportsState())
    }

    /**
     * Snapshot the latest expenses (with line items) for export. Always
     * returns a *copy* of the data so we don't race with the Flow.
     */
    suspend fun snapshotForExport(): List<Expense> = withContext(Dispatchers.IO) {
        repo.observeAllWithItems().first().map { repo.toDomain(it) }
    }

    suspend fun exportCsv(context: android.content.Context): File =
        exports.exportCsv(context, snapshotForExport())

    suspend fun exportPdf(context: android.content.Context): File =
        exports.exportPdf(context, snapshotForExport())

    suspend fun exportXlsx(context: android.content.Context): File =
        exports.exportXlsx(context, snapshotForExport())
}

@Composable
fun ReportsScreen(
    onBack: () -> Unit,
    onAdd: () -> Unit = {},
    onTab: (String) -> Unit = {},
    vm: ReportsViewModel = hiltViewModel()
) {
    val state by vm.state.collectAsState()
    val byId = state.categories.associateBy { it.id }
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var showExport by remember { mutableStateOf(false) }
    var exportStatus by remember { mutableStateOf<String?>(null) }
    var pending by remember { mutableStateOf(false) }

    GrainientBackground {
        Box(modifier = Modifier.fillMaxSize()) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(bottom = 120.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    horizontal = 24.dp, vertical = 16.dp
                ),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                item {
                    ExpenseLensTopBar(
                        title = "Reports",
                        onBack = onBack,
                        trailing = {
                            IconButton(onClick = { showExport = true }) {
                                Icon(Icons.Default.FileDownload, contentDescription = "Export")
                            }
                        }
                    )
                }
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        ExpenseLensSecondaryCard(modifier = Modifier.weight(1f)) {
                            Text("This week", style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(
                                Format.money(state.weekTotal),
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        ExpenseLensSecondaryCard(modifier = Modifier.weight(1f)) {
                            Text("This month", style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(
                                Format.money(state.monthTotal),
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
                item {
                    Text(
                        text = "By category (this month)",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                if (state.byCategory.isEmpty()) {
                    item {
                        ExpenseLensSecondaryCard {
                            Text(
                                "No expenses this month yet — add a bill to see the breakdown.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                } else {
                    items(state.byCategory.sortedByDescending { it.total }, key = { it.categoryId }) { t ->
                        ExpenseLensSecondaryCard(
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                                horizontal = 16.dp, vertical = 12.dp
                            )
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    modifier = Modifier
                                        .size(10.dp)
                                        .clip(CircleShape)
                                        .background(MaterialTheme.colorScheme.primary)
                                )
                                Spacer(Modifier.width(10.dp))
                                Text(
                                    text = byId[t.categoryId]?.name ?: "Other",
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.weight(1f)
                                )
                                Text(
                                    Format.money(t.total),
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }
                    }
                }
            }
            // Floating bottom nav (matches Dashboard, gives a 1-tap way to
            // jump to Expenses, Profile, or capture a new bill from Reports).
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
            ) {
                LiquidGlassBottomNav(
                    current = "reports",
                    onSelect = onTab,
                    onAdd = onAdd
                )
            }
        }
    }

    if (showExport) {
        AlertDialog(
            onDismissRequest = { showExport = false },
            title = { Text("Export report") },
            text = {
                Column {
                    Text(
                        "Save your spending as a file and share it.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(12.dp))
                    ExportOption(
                        icon = Icons.Default.TableChart,
                        title = "CSV (Excel / Sheets)",
                        subtitle = "Tab-separated. Best for accounting.",
                        enabled = !pending,
                        onClick = {
                            pending = true
                            scope.launch {
                                runCatching { vm.exportCsv(ctx) }
                                    .onSuccess { shareFile(ctx, it, "text/csv") }
                                    .onFailure { exportStatus = it.message }
                                pending = false
                                showExport = false
                            }
                        }
                    )
                    Spacer(Modifier.height(8.dp))
                    ExportOption(
                        icon = Icons.Default.PictureAsPdf,
                        title = "PDF",
                        subtitle = "A clean printable report.",
                        enabled = !pending,
                        onClick = {
                            pending = true
                            scope.launch {
                                runCatching { vm.exportPdf(ctx) }
                                    .onSuccess { shareFile(ctx, it, "application/pdf") }
                                    .onFailure { exportStatus = it.message }
                                pending = false
                                showExport = false
                            }
                        }
                    )
                    Spacer(Modifier.height(8.dp))
                    ExportOption(
                        icon = Icons.Default.TableChart,
                        title = "Excel (.xlsx)",
                        subtitle = "Two sheets: Summary and Line items.",
                        enabled = !pending,
                        onClick = {
                            pending = true
                            scope.launch {
                                runCatching { vm.exportXlsx(ctx) }
                                    .onSuccess { shareFile(ctx, it, "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet") }
                                    .onFailure { exportStatus = it.message }
                                pending = false
                                showExport = false
                            }
                        }
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { showExport = false }) { Text("Close") }
            }
        )
    }

    exportStatus?.let { msg ->
        AlertDialog(
            onDismissRequest = { exportStatus = null },
            title = { Text("Export failed") },
            text = { Text(msg) },
            confirmButton = {
                TextButton(onClick = { exportStatus = null }) { Text("OK") }
            }
        )
    }
}

@Composable
private fun ExportOption(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    enabled: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(GlassLight)
            .border(1.dp, GlassInner, RoundedCornerShape(16.dp))
            .let { if (enabled) it.clickable { onClick() } else it }
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
