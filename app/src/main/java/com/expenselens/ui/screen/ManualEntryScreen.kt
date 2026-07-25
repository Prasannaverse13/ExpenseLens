package com.expenselens.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Button
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.expenselens.data.prefs.AppPreferences
import com.expenselens.data.repo.ExpenseRepository
import com.expenselens.domain.model.CategoryType
import com.expenselens.domain.model.Expense
import com.expenselens.domain.model.LineItem
import com.expenselens.domain.model.PaymentMethod
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.time.LocalDate
import javax.inject.Inject

@HiltViewModel
class ManualEntryViewModel @Inject constructor(
    private val repo: ExpenseRepository,
    private val prefs: AppPreferences
) : ViewModel() {
    fun save(
        vendor: String,
        amount: Double,
        date: LocalDate,
        payment: PaymentMethod,
        category: CategoryType,
        notes: String
    ) {
        viewModelScope.launch {
            val currency = prefs.currency.first()
            val expense = Expense(
                vendor = vendor.ifBlank { "Manual" },
                billDate = date,
                totalAmount = amount,
                currency = currency,
                paymentMethod = payment,
                notes = notes,
                needsReview = false,
                confidence = 1f,
                lineItems = listOf(
                    LineItem(
                        description = vendor.ifBlank { "Manual entry" },
                        quantity = 1.0,
                        unitPrice = amount,
                        lineTotal = amount,
                        category = category,
                        categoryConfidence = 1f
                    )
                )
            )
            repo.save(expense)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ManualEntryScreen(
    onSaved: () -> Unit,
    onCancel: () -> Unit,
    vm: ManualEntryViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    var vendor by remember { mutableStateOf("") }
    var amount by remember { mutableStateOf("") }
    var date by remember { mutableStateOf(LocalDate.now().toString()) }
    var notes by remember { mutableStateOf("") }
    var category by remember { mutableStateOf(CategoryType.MISCELLANEOUS) }
    var payment by remember { mutableStateOf(PaymentMethod.CASH) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Manual entry") },
                navigationIcon = {
                    IconButton(onClick = onCancel) { Icon(Icons.Default.ArrowBack, null) }
                }
            )
        }
    ) { inner ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(inner)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Card(shape = RoundedCornerShape(16.dp)) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = vendor, onValueChange = { vendor = it },
                        label = { Text("Vendor / description") }, modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = amount, onValueChange = { amount = it.filter { ch -> ch.isDigit() || ch == '.' } },
                        label = { Text("Amount") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = date, onValueChange = { v -> runCatching { LocalDate.parse(v) }.onSuccess { date = v } },
                        label = { Text("Date (YYYY-MM-DD)") }, modifier = Modifier.fillMaxWidth()
                    )
                    Text("Category", style = MaterialTheme.typography.labelLarge)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        CategoryType.values().filter { it != CategoryType.UNKNOWN }.take(4).forEach { c ->
                            FilterChip(
                                selected = c == category,
                                onClick = { category = c },
                                label = { Text(c.displayName) }
                            )
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        CategoryType.values().filter { it != CategoryType.UNKNOWN }.drop(4).forEach { c ->
                            FilterChip(
                                selected = c == category,
                                onClick = { category = c },
                                label = { Text(c.displayName) }
                            )
                        }
                    }
                    Text("Payment method", style = MaterialTheme.typography.labelLarge)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        PaymentMethod.values().forEach { p ->
                            FilterChip(
                                selected = p == payment,
                                onClick = { payment = p },
                                label = { Text(p.displayName) }
                            )
                        }
                    }
                    OutlinedTextField(
                        value = notes, onValueChange = { notes = it },
                        label = { Text("Notes") }, modifier = Modifier.fillMaxWidth()
                    )
                }
            }
            Button(
                onClick = {
                    val amt = amount.toDoubleOrNull() ?: return@Button
                    val parsedDate = runCatching { LocalDate.parse(date) }.getOrElse { LocalDate.now() }
                    vm.save(vendor, amt, parsedDate, payment, category, notes)
                    onSaved()
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = amount.toDoubleOrNull() != null
            ) { Text("Save") }
        }
    }
}
