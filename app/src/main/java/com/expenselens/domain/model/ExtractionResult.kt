package com.expenselens.domain.model

/**
 * Raw extraction result produced by the OCR + parser pipeline.
 * The user reviews/edits this in the Review screen before it becomes an [Expense].
 */
data class ExtractionResult(
    val vendor: String = "",
    val billNumber: String? = null,
    val billDate: java.time.LocalDate = java.time.LocalDate.now(),
    val totalAmount: Double = 0.0,
    val taxAmount: Double? = null,
    val currency: String = "INR",
    val rawText: String = "",
    val lineItems: List<LineItem> = emptyList(),
    val fieldConfidences: Map<String, Float> = emptyMap(),
    val metadata: ExpenseMetadata? = null
) {
    val overallConfidence: Float
        get() = if (fieldConfidences.isEmpty()) 0.5f
        else fieldConfidences.values.average().toFloat()
}
