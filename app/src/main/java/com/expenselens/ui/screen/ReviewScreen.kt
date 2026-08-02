package com.expenselens.ui.screen

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
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
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.io.File
import java.time.format.DateTimeFormatter
import javax.inject.Inject

@HiltViewModel
class ReviewViewModel @Inject constructor(
    private val repo: ExpenseRepository,
    private val prefs: AppPreferences
) : ViewModel() {

    sealed class LoadState {
        data object Loading : LoadState()
        data class Loaded(val result: ExtractionResult) : LoadState()
        data object NotFound : LoadState()
    }

    private val _state = MutableStateFlow<LoadState>(LoadState.Loading)
    val state: StateFlow<LoadState> = _state.asStateFlow()

    fun load(context: android.content.Context, draftId: String) {
        viewModelScope.launch {
            _state.value = LoadState.Loading
            val res = DraftStore.load(context, draftId)
            if (res == null) {
                _state.value = LoadState.NotFound
                return@launch
            }
            val suggested = repo.suggestCategoryFor(res.vendor)
            val withCategory = if (suggested != null && res.lineItems.isNotEmpty()) {
                res.copy(lineItems = res.lineItems.map { it.copy(category = suggested) })
            } else res
            _state.value = LoadState.Loaded(withCategory)
        }
    }

    fun update(transform: (ExtractionResult) -> ExtractionResult) {
        val cur = _state.value
        if (cur is LoadState.Loaded) {
            _state.value = LoadState.Loaded(transform(cur.result))
        }
    }

    fun current(): ExtractionResult? =
        (_state.value as? LoadState.Loaded)?.result

    fun save(
        context: android.content.Context,
        draftId: String,
        notes: String,
        payment: PaymentMethod
    ) {
        viewModelScope.launch {
            val src = current() ?: return@launch
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
                metadata = src.metadata,
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

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun ReviewScreen(
    draftId: String,
    onDone: () -> Unit,
    onCancel: () -> Unit,
    vm: ReviewViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val state by vm.state.collectAsState()
    var notes by remember { mutableStateOf("") }
    var payment by remember { mutableStateOf(PaymentMethod.CASH) }
    var showRawEditor by remember { mutableStateOf(false) }
    var selectedTab by remember { mutableStateOf(0) }

    LaunchedEffect(draftId) { vm.load(context, draftId) }

    val sourceFile = remember(draftId) { DraftStore.sourceFile(context, draftId) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Review") },
                navigationIcon = {
                    IconButton(onClick = onCancel) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { inner ->
        when (val s = state) {
            is ReviewViewModel.LoadState.Loading -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(inner),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("Reading receiptâ€¦", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "This takes a few seconds â€” we're sending the bill to the extractor.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            is ReviewViewModel.LoadState.NotFound -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(inner)
                        .padding(24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            "We couldn't load this draft.",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "It may have been deleted. Try scanning the bill again.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(16.dp))
                        Button(onClick = onCancel) { Text("Back") }
                    }
                }
            }
            is ReviewViewModel.LoadState.Loaded -> ReviewLoadedBody(
                result = s.result,
                sourceFile = sourceFile,
                innerPadding = inner,
                notes = notes,
                onNotesChange = { notes = it },
                payment = payment,
                onPaymentChange = { payment = it },
                showRawEditor = showRawEditor,
                onToggleRawEditor = { showRawEditor = !showRawEditor },
                onUpdate = { vm.update(it) },
                selectedTab = selectedTab,
                onTabChange = { selectedTab = it },
                onSave = {
                    vm.save(context, draftId, notes, payment)
                    onDone()
                }
            )
        }
    }
}

private data class ReviewTab(val key: String, val label: String)

private val reviewTabs = listOf(
    ReviewTab("scanned", "Scanned"),
    ReviewTab("digital", "Digital bill"),
    ReviewTab("extracted", "Extracted data")
)

@Composable
private fun ReviewLoadedBody(
    result: ExtractionResult,
    sourceFile: File?,
    innerPadding: androidx.compose.foundation.layout.PaddingValues,
    notes: String,
    onNotesChange: (String) -> Unit,
    payment: PaymentMethod,
    onPaymentChange: (PaymentMethod) -> Unit,
    showRawEditor: Boolean,
    onToggleRawEditor: () -> Unit,
    onUpdate: ((ExtractionResult) -> ExtractionResult) -> Unit,
    selectedTab: Int,
    onTabChange: (Int) -> Unit,
    onSave: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(innerPadding)
    ) {
        // Title row
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                "Receipt understanding",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                "Tap a tab to inspect. Edit anything before saving.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        // Tab strip
        ReviewTabStrip(
            tabs = reviewTabs,
            selectedIndex = selectedTab,
            onSelect = onTabChange,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
        )

        // Tab content â€” each tab takes the full available height
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            when (selectedTab) {
                0 -> ScannedBillFull(sourceFile)
                1 -> DigitalBillFull(result)
                else -> ExtractedDataFull(result)
            }
        }

        // Sticky bottom: raw editor toggle + payment + notes + save
        ReviewActionFooter(
            result = result,
            showRawEditor = showRawEditor,
            onToggleRawEditor = onToggleRawEditor,
            onUpdate = onUpdate,
            notes = notes,
            onNotesChange = onNotesChange,
            payment = payment,
            onPaymentChange = onPaymentChange,
            onSave = onSave
        )
    }
}

@Composable
private fun ReviewTabStrip(
    tabs: List<ReviewTab>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(50)
            )
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        tabs.forEachIndexed { idx, tab ->
            val active = idx == selectedIndex
            val bg = if (active) MaterialTheme.colorScheme.primary
            else androidx.compose.ui.graphics.Color.Transparent
            val fg = if (active) MaterialTheme.colorScheme.onPrimary
            else MaterialTheme.colorScheme.onSurface
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(50))
                    .background(bg)
                    .clickable { onSelect(idx) }
                    .padding(vertical = 10.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = tab.label,
                    style = MaterialTheme.typography.labelLarge,
                    color = fg,
                    fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium
                )
            }
        }
    }
}

// ============================================================
// Tab 1 â€” Scanned Bill (the original image, full size)
// ============================================================
@Composable
private fun ScannedBillFull(sourceFile: File?) {
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = Color.Black),
        modifier = Modifier.fillMaxSize()
    ) {
        if (sourceFile != null && sourceFile.exists()) {
            val bmp = remember(sourceFile.absolutePath) {
                runCatching { BitmapFactory.decodeFile(sourceFile.absolutePath) }.getOrNull()
            }
            if (bmp != null) {
                Image(
                    bitmap = bmp.asImageBitmap(),
                    contentDescription = "Scanned receipt",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(8.dp)
                )
            } else {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("Cannot decode image", color = Color.White, style = MaterialTheme.typography.bodyMedium)
                }
            }
        } else {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("No image", color = Color.White.copy(alpha = 0.6f), style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

// ============================================================
// Tab 2 â€” Digital Bill (recreated typeset receipt, full size)
// ============================================================
@Composable
private fun DigitalBillFull(result: ExtractionResult) {
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        modifier = Modifier.fillMaxSize()
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(20.dp)
                .verticalScroll(rememberScrollState())
        ) {
            // Vendor logo / initials bubble
            val initials = result.vendor
                .split(' ', '\n', '\t')
                .filter { it.isNotBlank() }
                .take(2)
                .joinToString("") { it.first().uppercase() }
                .ifBlank { "â€”" }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(Color(0xFFE8F1ED)),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        initials,
                        style = MaterialTheme.typography.titleMedium,
                        color = Color(0xFF003329),
                        fontWeight = FontWeight.Bold
                    )
                }
                Spacer(Modifier.width(10.dp))
                Text(
                    text = result.vendor.uppercase().ifBlank { "VENDOR" },
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.titleLarge,
                    color = Color(0xFF003329)
                )
            }
            Spacer(Modifier.height(6.dp))
            val md = result.metadata
            if (md != null) {
                if (md.merchantPhone.isNotEmpty()) {
                    Text(
                        "Phone : ${md.merchantPhone.joinToString(", ")}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.DarkGray
                    )
                }
                md.fssaiNumber?.let {
                    Text(
                        "FSSAI : $it",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.DarkGray
                    )
                }
                Spacer(Modifier.height(8.dp))
            }
            Row(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.weight(1f)) {
                    result.billNumber?.let {
                        Text("Bill No : $it", style = MaterialTheme.typography.bodyMedium, color = Color.DarkGray)
                    }
                    val visit = result.metadata?.visitTime
                    if (visit != null) {
                        Text("Time : $visit", style = MaterialTheme.typography.bodyMedium, color = Color.DarkGray)
                    }
                }
                Text(
                    "Date : ${result.billDate.format(DateTimeFormatter.ofPattern("dd/MM/yyyy"))}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.DarkGray
                )
            }
            Spacer(Modifier.height(10.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(Color.LightGray)
            )
            Spacer(Modifier.height(6.dp))
            // Items table header
            Row(modifier = Modifier.fillMaxWidth()) {
                Text(
                    "Product",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(2f)
                )
                Text(
                    "Qty",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(0.6f),
                    textAlign = TextAlign.End
                )
                Text(
                    "Rate",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                    textAlign = TextAlign.End
                )
                Text(
                    "Amount",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1.2f),
                    textAlign = TextAlign.End
                )
            }
            Spacer(Modifier.height(4.dp))
            if (result.lineItems.isEmpty()) {
                Text(
                    "(no line items)",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.Gray
                )
            } else {
                result.lineItems.forEach { li ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        verticalAlignment = Alignment.Top
                    ) {
                        Text(
                            li.description,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(2f)
                        )
                        Text(
                            formatNum(li.quantity),
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(0.6f),
                            textAlign = TextAlign.End
                        )
                        Text(
                            formatNum(li.unitPrice),
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f),
                            textAlign = TextAlign.End
                        )
                        Text(
                            formatNum(li.lineTotal),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.weight(1.2f),
                            textAlign = TextAlign.End
                        )
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(Color.LightGray)
            )
            Spacer(Modifier.height(6.dp))
            Row(modifier = Modifier.fillMaxWidth()) {
                Text(
                    "Total Items: ${result.lineItems.size}",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    "${result.currency} ${formatNum(result.totalAmount)}",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF003329)
                )
            }
            Spacer(Modifier.height(12.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color(0xFF003329))
                    .padding(vertical = 12.dp, horizontal = 16.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "BILL AMOUNT",
                        style = MaterialTheme.typography.labelLarge,
                        color = Color.White,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        "${result.currency} ${formatNum(result.totalAmount)}",
                        style = MaterialTheme.typography.titleLarge,
                        color = Color.White,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
            Text(
                "THANK YOU, VISIT AGAIN.",
                style = MaterialTheme.typography.labelLarge,
                color = Color.Gray,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

// ============================================================
// Tab 3 â€” Extracted Data (clean key/value summary, full size)
// ============================================================
@Composable
private fun ExtractedDataFull(result: ExtractionResult) {
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        modifier = Modifier.fillMaxSize()
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(20.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            DataRow("Store Name", result.vendor.ifBlank { "â€”" })
            DataRow(
                "Phone",
                result.metadata?.merchantPhone?.joinToString(", ")?.ifBlank { "â€”" } ?: "â€”"
            )
            DataRow("FSSAI No", result.metadata?.fssaiNumber ?: "â€”")
            DataRow("Bill No", result.billNumber ?: "â€”")
            DataRow("Date", result.billDate.format(DateTimeFormatter.ofPattern("dd/MM/yyyy")))
            DataRow("Time", result.metadata?.visitTime ?: "â€”")
            DataRow("Items", null, list = result.lineItems)
            DataRow("Total Items", (result.metadata?.itemCount ?: result.lineItems.size).toString())
            DataRow("Total Amount", "${result.currency} ${formatNum(result.totalAmount)}")
            DataRow("Confidence", "${(result.overallConfidence * 100).toInt()}%")
        }
    }
}

@Composable
private fun ReviewActionFooter(
    result: ExtractionResult,
    showRawEditor: Boolean,
    onToggleRawEditor: () -> Unit,
    onUpdate: ((ExtractionResult) -> ExtractionResult) -> Unit,
    notes: String,
    onNotesChange: (String) -> Unit,
    payment: PaymentMethod,
    onPaymentChange: (PaymentMethod) -> Unit,
    onSave: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .windowInsetsPadding(WindowInsets.navigationBars)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        if (showRawEditor) {
            RawEditorCard(result = result, onChange = onUpdate)
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedButton(
                onClick = onToggleRawEditor,
                modifier = Modifier.weight(1f)
            ) {
                Text(if (showRawEditor) "Hide editor" else "Edit raw fields")
            }
        }
        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text(
                    "Payment",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.height(6.dp))
                PaymentChips(selected = payment, onSelect = onPaymentChange)
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = notes,
                    onValueChange = onNotesChange,
                    label = { Text("Notes") },
                    modifier = Modifier.fillMaxWidth()
                )
                if (result.overallConfidence < 0.7f) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Low confidence â€” please review fields carefully.",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }
        Button(
            onClick = onSave,
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp)
        ) {
            Text("Save expense", fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun DataRow(
    label: String,
    value: String?,
    list: List<LineItem>? = null,
    payment: String? = null
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = Color.Gray
        )
        when {
            value != null -> Text(
                value,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.SemiBold,
                color = Color(0xFF1C1C19)
            )
            list != null -> {
                if (list.isEmpty()) {
                    Text("-", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                } else {
                    list.forEach { li ->
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                li.description,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.weight(1f),
                                color = Color(0xFF1C1C19)
                            )
                            Text(
                                "x${formatNum(li.quantity)}",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color.Gray,
                                modifier = Modifier.padding(end = 6.dp)
                            )
                            Text(
                                formatNum(li.lineTotal),
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.SemiBold,
                                color = Color(0xFF1C1C19)
                            )
                        }
                    }
                }
            }
            payment != null -> Text(
                payment,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.SemiBold,
                color = Color(0xFF1C1C19)
            )
            else -> Text("-", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
        }
    }
}
// Raw editor (shown when user taps "Edit raw fields")
// ============================================================
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RawEditorCard(
    result: ExtractionResult,
    onChange: ((ExtractionResult) -> ExtractionResult) -> Unit
) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            OutlinedTextField(
                value = result.vendor,
                onValueChange = { v -> onChange { it.copy(vendor = v) } },
                label = { Text("Vendor") },
                modifier = Modifier.fillMaxWidth()
            )
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = result.billNumber.orEmpty(),
                    onValueChange = { v -> onChange { it.copy(billNumber = v.ifBlank { null }) } },
                    label = { Text("Bill no.") },
                    modifier = Modifier.weight(1f)
                )
                OutlinedTextField(
                    value = result.billDate.toString(),
                    onValueChange = { v ->
                        runCatching { java.time.LocalDate.parse(v) }
                            .onSuccess { d -> onChange { it.copy(billDate = d) } }
                    },
                    label = { Text("Date") },
                    modifier = Modifier.weight(1f)
                )
            }
            OutlinedTextField(
                value = result.totalAmount.toString(),
                onValueChange = { v ->
                    val d = v.toDoubleOrNull() ?: return@OutlinedTextField
                    onChange { it.copy(totalAmount = d) }
                },
                label = { Text("Total") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
            )
            if (result.lineItems.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text("Line items", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                result.lineItems.forEach { li ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(li.description, style = MaterialTheme.typography.bodySmall)
                            Text(
                                "qty ${formatNum(li.quantity)}  Ã— ${formatNum(li.unitPrice)}  = ${formatNum(li.lineTotal)}",
                                style = MaterialTheme.typography.labelSmall,
                                color = Color.Gray
                            )
                        }
                        Text(
                            "Delete",
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier
                                .clip(RoundedCornerShape(50))
                                .background(Color.Transparent)
                                .padding(8.dp)
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PaymentChips(selected: PaymentMethod, onSelect: (PaymentMethod) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        for (p in PaymentMethod.values()) {
            FilterChip(
                selected = p == selected,
                onClick = { onSelect(p) },
                label = { Text(p.displayName) }
            )
        }
    }
}

private fun formatNum(d: Double): String = if (d % 1.0 == 0.0) d.toInt().toString()
else String.format("%.2f", d)
