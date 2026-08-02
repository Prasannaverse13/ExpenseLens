package com.expenselens.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

@Database(
    entities = [
        ExpenseEntity::class,
        LineItemEntity::class,
        CategoryEntity::class,
        VendorCorrectionEntity::class,
        ExpenseMetadataEntity::class
    ],
    version = 3,
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class ExpenseLensDatabase : RoomDatabase() {
    abstract fun expenses(): ExpenseDao
    abstract fun lineItems(): LineItemDao
    abstract fun categories(): CategoryDao
    abstract fun vendorCorrections(): VendorCorrectionDao
    abstract fun expenseMetadata(): ExpenseMetadataDao
}
