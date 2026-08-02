package com.expenselens.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
import com.expenselens.domain.model.LineItem
import com.expenselens.domain.model.PaymentMethod
import com.expenselens.ui.common.ExpenseLensPrimaryButton
import com.expenselens.ui.common.ExpenseLensTopBar
import com.expenselens.ui.theme.GlassEdge
import com.expenselens.ui.theme.GlassInner
import com.expenselens.ui.theme.GlassLight
import com.expenselens.ui.common.GrainientBackground
import com.expenselens.ui.theme.Sage200
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

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ManualEntryScreen(
    onSaved: () -> Unit,
    onCancel: () -> Unit,
    onAdd: () -> Unit = {},
    onTab: (String) -> Unit = {},
    vm: ManualEntryViewModel = hiltViewModel()
) {
    var vendor by remember { mutableStateOf("") }
    var amount by remember { mutableStateOf("") }
    var date by remember { mutableStateOf(LocalDate.now().toString()) }
    var notes by remember { mutableStateOf("") }
    var category by remember { mutableStateOf(CategoryType.MISCELLANEOUS) }
    var payment by remember { mutableStateOf(PaymentMethod.CASH) }

    GrainientBackground {
        Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            ExpenseLensTopBar(title = "Manual entry", onBack = onCancel)
            LazyColumn(
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    start = 24.dp, end = 24.dp, top = 8.dp, bottom = 120.dp
                ),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                item {
                    Column(
                        modifier = Modifier
                            .clip(RoundedCornerShape(20.dp))
                            .background(GlassLight)
                            .border(1.dp, GlassInner, RoundedCornerShape(20.dp))
                            .padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        GlassField(
                            value = vendor,
                            onChange = { vendor = it },
                            label = "Vendor / description",
                            keyboard = KeyboardType.Text
                        )
                        GlassField(
                            value = amount,
                            onChange = { amount = it.filter { ch -> ch.isDigit() || ch == '.' } },
                            label = "Amount",
                            keyboard = KeyboardType.Decimal
                        )
                        GlassField(
                            value = date,
                            onChange = { v -> runCatching { LocalDate.parse(v) }.onSuccess { date = v } },
                            label = "Date (YYYY-MM-DD)",
                            keyboard = KeyboardType.Text
                        )
                        Text(
                            "Category",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                            fontWeight = FontWeight.SemiBold
                        )
                        androidx.compose.foundation.layout.FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            CategoryType.values().filter { it != CategoryType.UNKNOWN }
                                .forEach { c ->
                                    CategoryChipButton(
                                        label = c.displayName,
                                        selected = c == category,
                                        onClick = { category = c }
                                    )
                                }
                        }
                        Text(
                            "Payment method",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                            fontWeight = FontWeight.SemiBold
                        )
                        androidx.compose.foundation.layout.FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            PaymentMethod.values().forEach { p ->
                                CategoryChipButton(
                                    label = p.displayName,
                                    selected = p == payment,
                                    onClick = { payment = p }
                                )
                            }
                        }
                        GlassField(
                            value = notes,
                            onChange = { notes = it },
                            label = "Notes",
                            keyboard = KeyboardType.Text
                        )
                    }
                }
                item {
                    ExpenseLensPrimaryButton(
                        text = "Save",
                        onClick = {
                            val amt = amount.toDoubleOrNull() ?: return@ExpenseLensPrimaryButton
                            val parsedDate = runCatching { LocalDate.parse(date) }.getOrElse { LocalDate.now() }
                            vm.save(vendor, amt, parsedDate, payment, category, notes)
                            onSaved()
                        },
                        enabled = amount.toDoubleOrNull() != null
                    )
                }
            }
        }
        // Floating bottom nav so the user can switch tabs without losing
        // the manual entry (state is kept by the ViewModel scope above).
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
        ) {
            com.expenselens.ui.common.LiquidGlassBottomNav(
                current = "home",
                onSelect = onTab,
                onAdd = onAdd
            )
        }
        }
    }
}

@Composable
private fun GlassField(
    value: String,
    onChange: (String) -> Unit,
    label: String,
    keyboard: KeyboardType
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        shape = RoundedCornerShape(16.dp),
        keyboardOptions = KeyboardOptions(keyboardType = keyboard),
        colors = OutlinedTextFieldDefaults.colors(
            focusedContainerColor = androidx.compose.ui.graphics.Color.White,
            unfocusedContainerColor = androidx.compose.ui.graphics.Color.White,
            focusedBorderColor = GlassEdge,
            unfocusedBorderColor = GlassInner
        ),
        modifier = Modifier.fillMaxWidth()
    )
}

@Composable
private fun CategoryChipButton(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val bg = if (selected) Sage200.copy(alpha = 0.7f) else GlassLight
    val border = if (selected) MaterialTheme.colorScheme.primary else GlassInner
    Text(
        text = label,
        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
        color = if (selected) MaterialTheme.colorScheme.onSurface
                else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(bg)
            .border(1.dp, border, RoundedCornerShape(50))
            .clickable { onClick() }
            .padding(horizontal = 16.dp, vertical = 8.dp)
    )
}
