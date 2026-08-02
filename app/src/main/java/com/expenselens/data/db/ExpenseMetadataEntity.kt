package com.expenselens.data.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Optional per-expense metadata that the on-device parser does not produce
 * but a smart (LLM-assisted) extraction can. Stored in a separate table so the
 * core expenses schema stays stable and this table can be added/removed
 * without a destructive migration of bill history.
 */
@Entity(
    tableName = "expense_metadata",
    foreignKeys = [
        ForeignKey(
            entity = ExpenseEntity::class,
            parentColumns = ["id"],
            childColumns = ["expenseId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("expenseId", unique = true)]
)
data class ExpenseMetadataEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val expenseId: Long,
    /** Comma-separated phone numbers (receipts can list several). */
    val merchantPhones: String? = null,
    val merchantEmail: String? = null,
    val fssaiNumber: String? = null,
    val visitTime: String? = null,
    val itemCount: Int? = null,
    val source: String? = null,
    val extractedAt: Long = System.currentTimeMillis()
)
