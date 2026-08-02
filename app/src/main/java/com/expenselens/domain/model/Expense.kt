package com.expenselens.domain.model

import java.time.LocalDate
import java.time.LocalDateTime

data class Expense(
    val id: Long = 0L,
    val vendor: String,
    val billNumber: String? = null,
    val billDate: LocalDate = LocalDate.now(),
    val totalAmount: Double,
    val taxAmount: Double? = null,
    val currency: String = "INR",
    val paymentMethod: PaymentMethod = PaymentMethod.CASH,
    val notes: String? = null,
    val createdAt: LocalDateTime = LocalDateTime.now(),
    val updatedAt: LocalDateTime = LocalDateTime.now(),
    val confidence: Float = 1f,
    val needsReview: Boolean = false,
    val billFileUri: String? = null,
    val billMime: String? = null,
    val ocrText: String? = null,
    val lineItems: List<LineItem> = emptyList(),
    val metadata: ExpenseMetadata? = null
)
