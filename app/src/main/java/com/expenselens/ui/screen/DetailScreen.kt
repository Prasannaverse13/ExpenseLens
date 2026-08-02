package com.expenselens.ui.screen

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.expenselens.data.repo.ExpenseRepository
import com.expenselens.domain.model.Expense
import com.expenselens.domain.model.LineItem
import com.expenselens.export.ExportService
import com.expenselens.ui.common.ExpenseLensGlassCard
import com.expenselens.ui.common.ExpenseLensSecondaryCard
import com.expenselens.ui.common.ExpenseLensTopBar
import com.expenselens.ui.common.Format
import com.expenselens.ui.common.LiquidGlassBottomNav
import com.expenselens.ui.theme.GlassEdge
import com.expenselens.ui.theme.GlassInner
import com.expenselens.ui.common.GrainientBackground
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

@HiltViewModel
class DetailViewModel @Inject constructor(
    private val repo: ExpenseRepository,
    private val exports: ExportService
) : ViewModel() {

    private val _expense = MutableStateFlow<Expense?>(null)
    val expense: StateFlow<Expense?> = _expense.asStateFlow()

    fun load(id: Long) {
        viewModelScope.launch {
            val withItems = repo.byIdWithItems(id) ?: return@launch
            _expense.value = repo.toDomain(withItems)
        }
    }

    fun delete(id: Long, onDone: () -> Unit) {
        viewModelScope.launch {
            repo.delete(id); onDone()
        }
    }

    suspend fun exportCsv(context: android.content.Context, expense: Expense): File {
        val list = listOf(expense)
        return exports.exportCsv(context, list)
    }

    suspend fun exportXlsx(context: android.content.Context, expense: Expense): File {
        val list = listOf(expense)
        return exports.exportXlsx(context, list)
    }

    suspend fun exportPdf(context: android.content.Context, expense: Expense): File {
        val list = listOf(expense)
        return exports.exportPdf(context, list)
    }
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun DetailScreen(
    id: Long,
    onBack: () -> Unit,
    onAdd: () -> Unit = {},
    onTab: (String) -> Unit = {},
    vm: DetailViewModel = hiltViewModel()
) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val expense by vm.expense.collectAsState()
    val coroutineScope = rememberCoroutineScope()

    LaunchedEffect(id) { vm.load(id) }

    GrainientBackground {
        Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            val titleText = expense?.vendor?.ifBlank { "Detail" } ?: "Detail"
            val trailing: (@Composable () -> Unit)? = expense?.let { e ->
                {
                    IconButton(onClick = { vm.delete(e.id) { onBack() } }) {
                        Icon(Icons.Default.Delete, null)
                    }
                    IconButton(onClick = {
                        coroutineScope.launch {
                            val file = vm.exportCsv(ctx, e)
                            shareFile(ctx, file, "text/csv")
                        }
                    }) { Icon(Icons.Default.Share, null) }
                }
            }
            ExpenseLensTopBar(title = titleText, onBack = onBack, trailing = trailing)

            val e = expense
            if (e == null) return@Column
            LazyColumn(
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    start = 24.dp, end = 24.dp, top = 8.dp, bottom = 120.dp
                ),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                item {
                    ExpenseLensGlassCard {
                        Text(
                            "Total",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            Format.money(e.totalAmount, e.currency),
                            style = MaterialTheme.typography.displaySmall,
                            color = MaterialTheme.colorScheme.onSurface,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(Modifier.height(8.dp))
                        Text("Date: ${Format.date(e.billDate)}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (e.billNumber != null) {
                            Text("Bill no: ${e.billNumber}",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Text("Payment: ${e.paymentMethod.displayName}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (!e.notes.isNullOrBlank()) {
                            Text("Notes: ${e.notes}",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Text("Confidence: ${(e.confidence * 100).toInt()}%",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                item {
                    Text(
                        "Line items",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                items(e.lineItems) { li ->
                    ExpenseLensSecondaryCard {
                        LineItemRow(li, e.currency)
                    }
                }
            }
        }
        // Floating bottom nav so the user can jump out of a detail without
        // pressing back through every previous list.
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
private fun LineItemRow(li: LineItem, currency: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                li.description,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                "${li.quantity} × ${Format.money(li.unitPrice, currency)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                "Category: ${li.category.displayName}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Text(
            Format.money(li.lineTotal, currency),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.Bold
        )
    }
}

private fun shareFile(context: android.content.Context, file: File, mime: String) {
    val uri = FileProvider.getUriForFile(
        context, "${context.packageName}.fileprovider", file
    )
    val send = Intent(Intent.ACTION_SEND).apply {
        type = mime
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(send, "Share export"))
}
