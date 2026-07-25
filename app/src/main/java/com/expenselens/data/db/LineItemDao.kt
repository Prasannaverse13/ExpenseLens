package com.expenselens.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface LineItemDao {
    @Query("SELECT * FROM line_items WHERE expenseId = :expenseId")
    suspend fun forExpense(expenseId: Long): List<LineItemEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(items: List<LineItemEntity>): List<Long>
}
