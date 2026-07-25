package com.expenselens.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.expenselens.data.prefs.AppPreferences
import com.expenselens.data.repo.ExpenseRepository
import com.expenselens.domain.model.CategoryType
import com.expenselens.domain.model.Expense
import com.expenselens.domain.model.ExtractionResult
import com.expenselens.domain.model.LineItem
import com.expenselens.domain.model.PaymentMethod
import com.expenselens.ui.common.Format
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ReviewViewModel @Inject constructor(
    private val repo: ExpenseRepository,
    private val prefs: AppPreferences
) : ViewModel() {

    private val _extraction = MutableStateFlow<ExtractionResult?>(null)
    val extraction: StateFlow<ExtractionResult?> = _extraction.asStateFlow()

    fun load(context: android.content.Context, draftId: String) {
        viewModelScope.launch {
            val res = DraftStore.load(context, draftId)
            if (res != null) {
                val suggested = repo.suggestCategoryFor(res.vendor)
                if (suggested != null && res.lineItems.isNotEmpty()) {
                    val items = res.lineItems.map { it.copy(category = suggested) }
                    _extraction.value = res.copy(lineItems = items)
                } else {
                    _extraction.value = res
                }
            }
        }
    }

    fun update(transform: (ExtractionResult) -> ExtractionResult) {
        _extraction.value = _extraction.value?.let(transform)
    }

    fun save(
        context: android.content.Context,
        draftId: String,
        notes: String,
        payment: PaymentMethod
    ) {
        viewModelScope.launch {
            val src = _extraction.value ?: return@launch
            val currency = prefs.currency.first()
            val sourceFile = DraftStore.sourceFile(context, draftId)
            val sourceMime = DraftStore.sourceMime(context, draftId)
            val expense = Expense(
                vendor = src.vendor.ifBlank { "Unknown" },
                billNumber = src.billNumber,
                billDate = src.billDate,
                totalAmount = src.totalAmount,
                taxAmount = src.taxAmount,
                currency = src.currency.ifBlank { currency },
                paymentMethod = payment,
                notes = notes,
                confidence = src.overallConfidence,
                needsReview = src.overallConfidence < 0.7f,
                billFileUri = sourceFile?.absolutePath,
                billMime = sourceMime,
                ocrText = src.rawText,
                lineItems = src.lineItems.ifEmpty {
                    listOf(
                        LineItem(
                            description = "Whole bill",
                            quantity = 1.0,
                            unitPrice = src.totalAmount,
                            lineTotal = src.totalAmount,
                            category = CategoryType.MISCELLANEOUS,
                            categoryConfidence = 0.4f
                        )
                    )
                }
            )
            repo.save(expense)
            DraftStore.delete(context, draftId)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReviewScreen(
    draftId: String,
    onDone: () -> Unit,
    onCancel: () -> Unit,
    vm: ReviewViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val state by vm.extraction.collectAsState()
    var notes by remember { mutableStateOf("") }
    var payment by remember { mutableStateOf(PaymentMethod.CASH) }

    LaunchedEffect(draftId) { vm.load(context, draftId) }
    LaunchedEffect(state) {
        if (state == null) {
            // nothing to review, leave
            onCancel()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Review") },
                navigationIcon = {
                    IconButton(onClick = onCancel) { Icon(Icons.Default.ArrowBack, null) }
                }
            )
        }
    ) { inner ->
        val s = state ?: return@Scaffold
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(inner),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Card(shape = RoundedCornerShape(16.dp)) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        OutlinedTextField(
                            value = s.vendor,
                            onValueChange = { v -> vm.update { it.copy(vendor = v) } },
                            label = { Text("Vendor") },
                            modifier = Modifier.fillMaxWidth()
                        )
                        Row(modifier = Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(
                                value = s.billNumber.orEmpty(),
                                onValueChange = { v -> vm.update { it.copy(billNumber = v.ifBlank { null }) } },
                                label = { Text("Bill no.") },
                                modifier = Modifier.weight(1f)
                            )
                            OutlinedTextField(
                                value = s.billDate.toString(),
                                onValueChange = { v ->
                                    runCatching { java.time.LocalDate.parse(v) }
                                        .onSuccess { d -> vm.update { it.copy(billDate = d) } }
                                },
                                label = { Text("Date (YYYY-MM-DD)") },
                                modifier = Modifier.weight(1f)
                            )
                        }
                        OutlinedTextField(
                            value = s.totalAmount.toString(),
                            onValueChange = { v ->
                                val d = v.toDoubleOrNull() ?: return@OutlinedTextField
                                vm.update { it.copy(totalAmount = d) }
                            },
                            label = { Text("Total amount") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                        )
                        s.taxAmount?.let {
                            OutlinedTextField(
                                value = it.toString(),
                                onValueChange = { v ->
                                    val d = v.toDoubleOrNull() ?: return@OutlinedTextField
                                    vm.update { it.copy(taxAmount = d) }
                                },
                                label = { Text("Tax") },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                            )
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("Payment:", modifier = Modifier.padding(end = 8.dp))
                            PaymentChips(selected = payment, onSelect = { payment = it })
                        }
                        OutlinedTextField(
                            value = notes,
                            onValueChange = { notes = it },
                            label = { Text("Notes") },
                            modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                        )
                        if (s.overallConfidence < 0.7f) {
                            Text(
                                "Low confidence — please review fields carefully.",
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.padding(top = 8.dp)
                            )
                        }
                    }
                }
            }
            item { Text("Line items", style = MaterialTheme.typography.titleMedium) }
            items(s.lineItems) { li ->
                LineItemEditor(
                    item = li,
                    onChange = { updated ->
                        vm.update { res ->
                            res.copy(
                                lineItems = res.lineItems.map { if (it === li) updated else it }
                            )
                        }
                    },
                    onRemove = {
                        vm.update { it.copy(lineItems = it.lineItems - li) }
                    }
                )
            }
            item {
                OutlinedButton(
                    onClick = {
                        vm.update { res ->
                            res.copy(
                                lineItems = res.lineItems + LineItem(
                                    description = "New item",
                                    quantity = 1.0,
                                    unitPrice = 0.0,
                                    lineTotal = 0.0,
                                    category = CategoryType.MISCELLANEOUS,
                                    categoryConfidence = 0.5f
                                )
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("+ Add line item") }
            }
            item {
                Button(
                    onClick = {
                        vm.save(context, draftId, notes, payment)
                        onDone()
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Save expense") }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PaymentChips(selected: PaymentMethod, onSelect: (PaymentMethod) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        PaymentMethod.values().forEach { p ->
            FilterChip(
                selected = p == selected,
                onClick = { onSelect(p) },
                label = { Text(p.displayName) }
            )
        }
    }
}

@Composable
private fun LineItemEditor(
    item: LineItem,
    onChange: (LineItem) -> Unit,
    onRemove: () -> Unit
) {
    Card(shape = RoundedCornerShape(12.dp)) {
        Column(modifier = Modifier.padding(12.dp)) {
            OutlinedTextField(
                value = item.description,
                onValueChange = { onChange(item.copy(description = it)) },
                label = { Text("Description") },
                modifier = Modifier.fillMaxWidth()
            )
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = item.quantity.toString(),
                    onValueChange = { v ->
                        val d = v.toDoubleOrNull() ?: return@OutlinedTextField
                        onChange(item.copy(quantity = d, lineTotal = d * item.unitPrice))
                    },
                    label = { Text("Qty") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.weight(1f)
                )
                OutlinedTextField(
                    value = item.unitPrice.toString(),
                    onValueChange = { v ->
                        val d = v.toDoubleOrNull() ?: return@OutlinedTextField
                        onChange(item.copy(unitPrice = d, lineTotal = d * item.quantity))
                    },
                    label = { Text("Unit") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.weight(1f)
                )
                OutlinedTextField(
                    value = item.lineTotal.toString(),
                    onValueChange = { v ->
                        val d = v.toDoubleOrNull() ?: return@OutlinedTextField
                        onChange(item.copy(lineTotal = d))
                    },
                    label = { Text("Total") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.weight(1f)
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                CategoryPicker(item.category) { c -> onChange(item.copy(category = c)) }
                Box(modifier = Modifier.weight(1f))
                IconButton(onClick = onRemove) { Icon(Icons.Default.Delete, null) }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CategoryPicker(
    current: CategoryType,
    onSelect: (CategoryType) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        AssistChip(
            onClick = { expanded = true },
            label = { Text(current.displayName) }
        )
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            CategoryType.values()
                .filter { it != CategoryType.UNKNOWN }
                .forEach { c ->
                    DropdownMenuItem(
                        text = { Text(c.displayName) },
                        onClick = { onSelect(c); expanded = false }
                    )
                }
        }
    }
}
