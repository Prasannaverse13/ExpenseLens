package com.expenselens.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.NotificationsNone
import androidx.compose.material.icons.filled.QrCode
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.expenselens.data.db.CategoryEntity
import com.expenselens.data.db.CategoryTotal
import com.expenselens.data.db.ExpenseEntity
import com.expenselens.data.insights.SpendingInsight
import com.expenselens.data.insights.SpendingInsightsEngine
import com.expenselens.data.repo.ExpenseRepository
import com.expenselens.export.ExportService
import com.expenselens.ui.common.BrandAvatar
import com.expenselens.ui.common.DonutChart
import com.expenselens.ui.common.DonutSlice
import com.expenselens.ui.common.ExpenseLensActionTile
import com.expenselens.ui.common.ExpenseLensGlassCard
import com.expenselens.ui.common.ExpenseLensSecondaryCard
import com.expenselens.ui.common.Format
import com.expenselens.ui.common.GrainientBackground
import com.expenselens.ui.common.LiquidGlassBottomNav
import com.expenselens.ui.common.shareFile
import com.expenselens.ui.theme.Emerald400
import com.expenselens.ui.theme.Emerald700
import com.expenselens.ui.theme.Emerald800
import com.expenselens.ui.theme.Emerald500
import com.expenselens.ui.theme.GlassEdge
import com.expenselens.ui.theme.GlassInner
import com.expenselens.ui.theme.Sage200
import com.expenselens.ui.theme.Amber400
import com.expenselens.ui.theme.Cream400
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
import java.time.format.DateTimeFormatter
import java.util.Locale
import javax.inject.Inject

data class DashboardState(
    val month: Double = 0.0,
    val lastMonth: Double = 0.0,
    val categoryTotals: List<CategoryTotal> = emptyList(),
    val categories: List<CategoryEntity> = emptyList(),
    val recent: List<ExpenseEntity> = emptyList(),
    val userName: String = "",
    val userEmail: String = "",
    val insights: List<SpendingInsight> = emptyList(),
    val isPremium: Boolean = false
)

@HiltViewModel
class DashboardViewModel @Inject constructor(
    private val repo: ExpenseRepository,
    private val exports: ExportService,
    private val prefs: com.expenselens.data.prefs.AppPreferences
) : ViewModel() {

    private val userName = prefs.driveAccountName
    private val userEmail = prefs.driveAccount

    val state: StateFlow<DashboardState> = run {
        viewModelScope.launch {
            repo.seedCategoriesIfEmpty()
            repo.seedMissingCategories()
        }
        val today = LocalDate.now()
        val monthStart = today.withDayOfMonth(1)
        val lastMonthStart = monthStart.minusMonths(1)
        val lastMonthEnd = monthStart.minusDays(1)
        // Use the array-form combine() so we can fan in 7 flows (the
        // vararg overload only goes up to 5).
        combine(
            repo.observeCategoryTotals(monthStart, today),
            repo.observeCategoryTotals(lastMonthStart, lastMonthEnd),
            repo.observeAll(),
            repo.categoryFlow(),
            userName,
            userEmail,
            prefs.isPremium
        ) { values ->
            @Suppress("UNCHECKED_CAST")
            val thisMonthTotals = values[0] as List<CategoryTotal>
            @Suppress("UNCHECKED_CAST")
            val prevMonthTotals = values[1] as List<CategoryTotal>
            @Suppress("UNCHECKED_CAST")
            val all = values[2] as List<ExpenseEntity>
            @Suppress("UNCHECKED_CAST")
            val cats = values[3] as List<CategoryEntity>
            val name = values[4] as String
            val email = values[5] as String
            val premium = values[6] as Boolean

            // Insights use only the in-month subset, plus the pre-aggregated
            // totals — pure computation, no extra queries.
            val thisMonthExpenses = all.filter {
                val d = it.billDate
                !d.isBefore(monthStart) && !d.isAfter(today)
            }
            // Pass `all` so the engine can detect recurring vendors
            // (UpcomingRecurring, UnusedRecurring) and per-vendor MoM.
            val insights = SpendingInsightsEngine.generate(
                thisMonth = thisMonthExpenses,
                thisMonthTotals = thisMonthTotals,
                lastMonthTotals = prevMonthTotals,
                allExpenses = all,
                categories = cats,
                today = today
            )

            DashboardState(
                month = thisMonthTotals.sumOf { it.total },
                lastMonth = prevMonthTotals.sumOf { it.total },
                categoryTotals = thisMonthTotals,
                categories = cats,
                recent = all.take(8),
                userName = name,
                userEmail = email,
                insights = insights,
                isPremium = premium
            )
        }.stateIn(viewModelScope, SharingStarted.Eagerly, DashboardState())
    }

    suspend fun snapshotForExport(): List<com.expenselens.domain.model.Expense> =
        withContext(Dispatchers.IO) {
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
fun DashboardScreen(
    onAdd: () -> Unit,
    onOpenItem: (Long) -> Unit,
    onOpenCapture: () -> Unit,
    onOpenReports: () -> Unit,
    onOpenExpenses: () -> Unit,
    onOpenProfile: () -> Unit,
    vm: DashboardViewModel = hiltViewModel()
) {
    val state by vm.state.collectAsState()
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var showExport by remember { mutableStateOf(false) }
    var exportError by remember { mutableStateOf<String?>(null) }
    var pending by remember { mutableStateOf(false) }
    val today = LocalDate.now()
    val last24hCount = state.recent.count {
        it.createdAt.toLocalDate() == today ||
            it.createdAt.toLocalDate() == today.minusDays(1)
    }

    GrainientBackground {
        Box(modifier = Modifier.fillMaxSize()) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(bottom = 120.dp),  // room for floating nav + nav-bar insets
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    horizontal = 24.dp, vertical = 16.dp
                ),
                verticalArrangement = Arrangement.spacedBy(20.dp)
            ) {
                item {
                    GreetingHeader(
                        userName = state.userName.ifBlank { "there" },
                        addedToday = last24hCount,
                        onExport = { showExport = true }
                    )
                }

                item {
                    MonthTotalCard(
                        month = state.month,
                        lastMonth = state.lastMonth
                    )
                }

                if (state.insights.isNotEmpty()) {
                    item {
                        InsightsCarousel(
                            insights = state.insights,
                            isPremium = state.isPremium
                        )
                    }
                }

                item {
                    ActionRow(
                        onScan = onOpenCapture,
                        onUpload = onOpenCapture,
                        onAdd = onAdd,
                        onReports = onOpenReports
                    )
                }

                item {
                    CategorySplitCard(
                        totals = state.categoryTotals,
                        categories = state.categories
                    )
                }

                item {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Recent Activity",
                            style = MaterialTheme.typography.titleLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.weight(1f)
                        )
                        if (last24hCount > 0) {
                            Box(
                                modifier = Modifier
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.primary)
                                    .padding(horizontal = 10.dp, vertical = 4.dp)
                            ) {
                                Text(
                                    text = "+$last24hCount new",
                                    color = MaterialTheme.colorScheme.onPrimary,
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }
                    }
                }

                items(state.recent, key = { it.id }) { item ->
                    RecentActivityRow(
                        item = item,
                        categories = state.categories,
                        onClick = { onOpenItem(item.id) }
                    )
                }
            }

            // Floating bottom nav
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
            ) {
                LiquidGlassBottomNav(
                    current = "home",
                    onSelect = { key ->
                        when (key) {
                            "home" -> { /* already here */ }
                            "reports" -> onOpenReports()
                            "expenses" -> onOpenExpenses()
                            "profile" -> onOpenProfile()
                        }
                    },
                    onAdd = onAdd
                )
            }
        }
    }

    if (showExport) {
        AlertDialog(
            onDismissRequest = { showExport = false },
            title = { Text("Export spending") },
            text = {
                Column {
                    Text(
                        "Save your spending as a file. Choose CSV, PDF, or Excel.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(12.dp))
                    DashboardExportOption(
                        title = "CSV",
                        subtitle = "Tab-separated. Best for accounting.",
                        enabled = !pending,
                        onClick = {
                            pending = true
                            scope.launch {
                                runCatching { vm.exportCsv(ctx) }
                                    .onSuccess { shareFile(ctx, it, "text/csv") }
                                    .onFailure { exportError = it.message ?: "Export failed" }
                                pending = false
                                showExport = false
                            }
                        }
                    )
                    Spacer(Modifier.height(8.dp))
                    DashboardExportOption(
                        title = "PDF",
                        subtitle = "A clean printable report.",
                        enabled = !pending,
                        onClick = {
                            pending = true
                            scope.launch {
                                runCatching { vm.exportPdf(ctx) }
                                    .onSuccess { shareFile(ctx, it, "application/pdf") }
                                    .onFailure { exportError = it.message ?: "Export failed" }
                                pending = false
                                showExport = false
                            }
                        }
                    )
                    Spacer(Modifier.height(8.dp))
                    DashboardExportOption(
                        title = "Excel (.xlsx)",
                        subtitle = "Two sheets: Summary and Line items.",
                        enabled = !pending,
                        onClick = {
                            pending = true
                            scope.launch {
                                runCatching { vm.exportXlsx(ctx) }
                                    .onSuccess { shareFile(ctx, it, "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet") }
                                    .onFailure { exportError = it.message ?: "Export failed" }
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
    exportError?.let { msg ->
        AlertDialog(
            onDismissRequest = { exportError = null },
            title = { Text("Export failed") },
            text = { Text(msg) },
            confirmButton = { TextButton(onClick = { exportError = null }) { Text("OK") } }
        )
    }
}

@Composable
private fun DashboardExportOption(
    title: String,
    subtitle: String,
    enabled: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(com.expenselens.ui.theme.GlassLight)
            .border(1.dp, com.expenselens.ui.theme.GlassInner, RoundedCornerShape(16.dp))
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
            Icon(Icons.Default.FileDownload, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
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

@Composable
private fun GreetingHeader(
    userName: String = "",
    addedToday: Int = 0,
    onExport: () -> Unit = {}
) {
    val firstName = userName.split(' ').firstOrNull().orEmpty()
    val initials = firstName.firstOrNull()?.uppercase() ?: "EX"
    val greeting = greetingForHour()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.statusBars)
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        // Top row: avatar + name + action buttons
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            BrandAvatar(size = 48.dp, initials = initials)
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "$greeting,",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1
                )
                Text(
                    text = firstName.ifBlank { "there" },
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                )
            }
            Spacer(Modifier.width(8.dp))
            CircleIconButton(
                icon = Icons.Default.FileDownload,
                onClick = onExport,
                contentDescription = "Export"
            )
            Spacer(Modifier.width(8.dp))
            CircleIconButton(
                icon = Icons.Default.NotificationsNone,
                onClick = { /* notifications */ },
                contentDescription = "Notifications"
            )
        }
        // Second row: badge below the name (so it never crowds the buttons)
        if (addedToday > 0) {
            Spacer(Modifier.height(8.dp))
            Box(
                modifier = Modifier
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary)
                    .padding(horizontal = 12.dp, vertical = 4.dp)
            ) {
                Text(
                    text = "+$addedToday new today",
                    color = MaterialTheme.colorScheme.onPrimary,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
}

@Composable
private fun CircleIconButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit,
    contentDescription: String?
) {
    Box(
        modifier = Modifier
            .size(44.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, GlassInner, CircleShape)
            .clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Icon(
            icon,
            contentDescription = contentDescription,
            tint = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.size(20.dp)
        )
    }
}

private fun greetingForHour(): String {
    val h = java.time.LocalTime.now().hour
    return when {
        h in 5..11 -> "Good morning"
        h in 12..16 -> "Good afternoon"
        h in 17..21 -> "Good evening"
        else -> "Hello"
    }
}

@Composable
private fun MonthTotalCard(month: Double, lastMonth: Double) {
    val pct = if (lastMonth > 0.0) ((month - lastMonth) / lastMonth * 100.0) else 0.0
    val pctText = if (pct >= 0) "+${pct.toInt()}%" else "${pct.toInt()}%"
    ExpenseLensGlassCard(contentPadding = androidx.compose.foundation.layout.PaddingValues(20.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "THIS MONTH",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f)
            )
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(Sage200.copy(alpha = 0.5f))
                    .padding(horizontal = 10.dp, vertical = 4.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.ArrowDownward,
                        contentDescription = null,
                        tint = Emerald700,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(Modifier.width(2.dp))
                    Text(
                        text = pctText,
                        style = MaterialTheme.typography.labelSmall,
                        color = Emerald700,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                text = Format.money(month),
                style = MaterialTheme.typography.displaySmall,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.width(4.dp))
            Text(
                text = ".00",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 6.dp)
            )
        }
        Text(
            text = "Total spent across all accounts",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun ActionRow(
    onScan: () -> Unit,
    onUpload: () -> Unit,
    onAdd: () -> Unit,
    onReports: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        ActionTile(
            label = "Scan",
            icon = Icons.Default.QrCode,
            modifier = Modifier.weight(1f),
            onClick = onScan
        )
        ActionTile(
            label = "Upload",
            icon = Icons.Default.FileUpload,
            modifier = Modifier.weight(1f),
            onClick = onUpload
        )
        ActionTile(
            label = "Add",
            icon = Icons.Default.Edit,
            modifier = Modifier.weight(1f),
            onClick = onAdd
        )
        ActionTile(
            label = "Reports",
            icon = Icons.Default.BarChart,
            modifier = Modifier.weight(1f),
            onClick = onReports
        )
    }
}

@Composable
private fun ActionTile(
    label: String,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(20.dp))
            .background(com.expenselens.ui.theme.GlassLight)
            .border(1.dp, GlassInner, RoundedCornerShape(20.dp))
            .clickable { onClick() }
            .padding(vertical = 16.dp, horizontal = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surface),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.size(20.dp)
            )
        }
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
private fun CategorySplitCard(
    totals: List<CategoryTotal>,
    categories: List<CategoryEntity>
) {
    val byId = categories.associateBy { it.id }
    val slices = totals.sortedByDescending { it.total }.take(4).map { t ->
        val name = byId[t.categoryId]?.name ?: "Other"
        val color = when (t.categoryId.toInt() % 5) {
            0 -> Emerald700
            1 -> Sage200
            2 -> Amber400
            3 -> Cream400
            else -> Emerald400
        }
        DonutSlice(name, t.total.toFloat(), color)
    }
    ExpenseLensSecondaryCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Category Split",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = "•••",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.height(12.dp))
        if (slices.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(140.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "Add a bill to see category breakdown",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            DonutChart(
                slices = slices,
                totalLabel = "₹${(totals.sumOf { it.total } / 1000).toInt()}.${(totals.sumOf { it.total } % 1000 / 100).toInt()}K"
            )
        }
    }
}

@Composable
private fun RecentActivityRow(
    item: ExpenseEntity,
    categories: List<CategoryEntity>,
    onClick: () -> Unit
) {
    val byId = categories.associateBy { it.id }
    val categoryName = byId[item.categoryId]?.name ?: "Other"
    val timeStr = formatTime(item.createdAt.toLocalTime())
    ExpenseLensSecondaryCard(
        modifier = Modifier.clickable { onClick() },
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            horizontal = 16.dp, vertical = 14.dp
        )
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
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.vendor.ifBlank { "(no vendor)" },
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = "${formatDate(item.billDate)} • $timeStr",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = "-${Format.money(item.totalAmount, item.currency)}",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = categoryName,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

private fun formatDate(d: LocalDate): String {
    val today = LocalDate.now()
    return when {
        d == today -> "Today"
        d == today.minusDays(1) -> "Yesterday"
        else -> d.format(DateTimeFormatter.ofPattern("dd MMM", Locale.ENGLISH))
    }
}

private fun formatTime(t: java.time.LocalTime): String =
    t.format(DateTimeFormatter.ofPattern("hh:mm a", Locale.ENGLISH))
