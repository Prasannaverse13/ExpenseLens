package com.expenselens.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.time.LocalDate
import java.time.LocalDateTime

@Entity(
    tableName = "expenses",
    indices = [Index("billDate"), Index("vendor"), Index("categoryId")]
)
data class ExpenseEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val vendor: String,
    val billNumber: String?,
    val billDate: LocalDate,
    val totalAmount: Double,
    val taxAmount: Double?,
    val currency: String,
    val paymentMethod: String,
    val notes: String?,
    val createdAt: LocalDateTime,
    val updatedAt: LocalDateTime,
    val confidence: Float,
    val needsReview: Boolean,
    val categoryId: Long,
    val billFileUri: String?,
    val billMime: String?,
    val ocrText: String?
)
