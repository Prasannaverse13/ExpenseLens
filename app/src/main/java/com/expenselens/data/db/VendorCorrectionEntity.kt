package com.expenselens.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "vendor_corrections",
    indices = [Index(value = ["vendorKey", "categoryId"], unique = true)]
)
data class VendorCorrectionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val vendorKey: String,
    val categoryId: Long,
    val hitCount: Int = 1,
    val lastUsedAt: Long = System.currentTimeMillis()
)
