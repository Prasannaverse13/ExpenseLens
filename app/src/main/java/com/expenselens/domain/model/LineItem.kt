package com.expenselens.domain.model

data class LineItem(
    val id: Long = 0L,
    val expenseId: Long = 0L,
    val description: String,
    val quantity: Double = 1.0,
    val unitPrice: Double = 0.0,
    val lineTotal: Double = 0.0,
    val category: CategoryType = CategoryType.UNKNOWN,
    val categoryConfidence: Float = 0f
)
