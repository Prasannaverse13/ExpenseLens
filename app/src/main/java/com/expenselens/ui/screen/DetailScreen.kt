package com.expenselens.ui.screen

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.expenselens.data.repo.ExpenseRepository
import com.expenselens.domain.model.CategoryType
import com.expenselens.domain.model.Expense
import com.expenselens.export.ExportService
import com.expenselens.ui.common.Format
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

    fun exportCsv(context: android.content.Context, expense: Expense): File {
        val list = listOf(expense)
        return exports.exportCsv(context, list)
    }

    fun exportXlsx(context: android.content.Context, expense: Expense): File {
        val list = listOf(expense)
        return exports.exportXlsx(context, list)
    }

    fun exportPdf(context: android.content.Context, expense: Expense): File {
        val list = listOf(expense)
        return exports.exportPdf(context, list)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetailScreen(
    id: Long,
    onBack: () -> Unit,
    vm: DetailViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val expense by vm.expense.collectAsState()

    LaunchedEffect(id) { vm.load(id) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(expense?.vendor ?: "Detail") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, null) }
                },
                actions = {
                    val e = expense
                    if (e != null) {
                        IconButton(onClick = { vm.delete(e.id) { onBack() } }) {
                            Icon(Icons.Default.Delete, null)
                        }
                        IconButton(onClick = {
                            val file = vm.exportCsv(context, e)
                            shareFile(context, file, "text/csv")
                        }) { Icon(Icons.Default.Share, null) }
                    }
                }
            )
        }
    ) { inner ->
        val e = expense
        if (e == null) return@Scaffold
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(inner)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Card(shape = RoundedCornerShape(20.dp)) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Total", style = MaterialTheme.typography.labelLarge)
                    Text(
                        Format.money(e.totalAmount, e.currency),
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text("Date: ${Format.date(e.billDate)}")
                    e.billNumber?.let { Text("Bill no: $it") }
                    Text("Payment: ${e.paymentMethod.displayName}")
                    e.notes?.takeIf { it.isNotBlank() }?.let { Text("Notes: $it") }
                    Text("Confidence: ${(e.confidence * 100).toInt()}%")
                }
            }
            Text("Line items", style = MaterialTheme.typography.titleMedium)
            e.lineItems.forEach { li ->
                Card(shape = RoundedCornerShape(12.dp)) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(li.description, fontWeight = FontWeight.SemiBold)
                            Text(
                                "${li.quantity} × ${Format.money(li.unitPrice, e.currency)}",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                "Category: ${li.category.displayName}",
                                style = MaterialTheme.typography.labelMedium
                            )
                        }
                        Text(Format.money(li.lineTotal, e.currency), fontWeight = FontWeight.Bold)
                    }
                }
            }
            e.billFileUri?.let { path ->
                Text("Original: $path", style = MaterialTheme.typography.labelSmall)
            }
        }
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
