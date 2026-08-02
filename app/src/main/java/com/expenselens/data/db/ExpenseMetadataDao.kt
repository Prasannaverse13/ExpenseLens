package com.expenselens.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface ExpenseMetadataDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(meta: ExpenseMetadataEntity): Long

    @Query("SELECT * FROM expense_metadata WHERE expenseId = :expenseId LIMIT 1")
    suspend fun forExpense(expenseId: Long): ExpenseMetadataEntity?

    @Query("DELETE FROM expense_metadata WHERE expenseId = :expenseId")
    suspend fun deleteFor(expenseId: Long)
}
