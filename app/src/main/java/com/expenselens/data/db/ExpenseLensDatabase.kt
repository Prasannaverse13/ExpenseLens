package com.expenselens.data.db

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [
        ExpenseEntity::class,
        LineItemEntity::class,
        CategoryEntity::class,
        VendorCorrectionEntity::class
    ],
    version = 1,
    exportSchema = false
)
abstract class ExpenseLensDatabase : RoomDatabase() {
    abstract fun expenses(): ExpenseDao
    abstract fun lineItems(): LineItemDao
    abstract fun categories(): CategoryDao
    abstract fun vendorCorrections(): VendorCorrectionDao
}
