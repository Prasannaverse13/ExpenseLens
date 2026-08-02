package com.expenselens.data.db

import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Relation
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

data class ExpenseWithLineItems(
    @Embedded val expense: ExpenseEntity,
    @Relation(parentColumn = "id", entityColumn = "expenseId")
    val lineItems: List<LineItemEntity>
)

@Dao
interface ExpenseDao {
    @Query("SELECT * FROM expenses ORDER BY billDate DESC, id DESC")
    fun observeAll(): Flow<List<ExpenseEntity>>

    @Transaction
    @Query("SELECT * FROM expenses ORDER BY billDate DESC, id DESC")
    fun observeAllWithItems(): Flow<List<ExpenseWithLineItems>>

    @Transaction
    @Query("SELECT * FROM expenses WHERE id = :id")
    suspend fun byIdWithItems(id: Long): ExpenseWithLineItems?

    @Query("SELECT * FROM expenses WHERE id = :id")
    suspend fun byId(id: Long): ExpenseEntity?

    @Query(
        """
        SELECT * FROM expenses
        WHERE (:from IS NULL OR billDate >= :from)
          AND (:to   IS NULL OR billDate <= :to)
          AND (:vendorQuery = '' OR vendor LIKE '%' || :vendorQuery || '%')
          AND (:minAmount IS NULL OR totalAmount >= :minAmount)
          AND (:maxAmount IS NULL OR totalAmount <= :maxAmount)
          AND (:categoryId IS NULL OR categoryId = :categoryId OR id IN (SELECT expenseId FROM line_items WHERE categoryId = :categoryId))
        ORDER BY billDate DESC, id DESC
        """
    )
    fun search(
        from: java.time.LocalDate?,
        to: java.time.LocalDate?,
        vendorQuery: String,
        minAmount: Double?,
        maxAmount: Double?,
        categoryId: Long?
    ): Flow<List<ExpenseEntity>>

    @Query("SELECT COALESCE(SUM(totalAmount), 0) FROM expenses WHERE billDate = :date")
    fun observeTotalForDate(date: java.time.LocalDate): Flow<Double>

    @Query(
        """
        SELECT categoryId AS categoryId, COALESCE(SUM(totalAmount), 0) AS total
        FROM expenses WHERE billDate BETWEEN :from AND :to
        GROUP BY categoryId
        """
    )
    fun observeCategoryTotals(
        from: java.time.LocalDate,
        to: java.time.LocalDate
    ): Flow<List<CategoryTotal>>

    @Query(
        """
        SELECT billDate AS day, COALESCE(SUM(totalAmount), 0) AS total
        FROM expenses WHERE billDate BETWEEN :from AND :to
        GROUP BY billDate ORDER BY billDate ASC
        """
    )
    fun observeDailyTotals(
        from: java.time.LocalDate,
        to: java.time.LocalDate
    ): Flow<List<DailyTotal>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertExpense(expense: ExpenseEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertLineItems(items: List<LineItemEntity>): List<Long>

    @Query("DELETE FROM line_items")
    suspend fun deleteAllLineItems()

    @Query("DELETE FROM expenses")
    suspend fun deleteAllExpenses()

    @Update
    suspend fun updateExpense(expense: ExpenseEntity)

    @Query("DELETE FROM expenses WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM line_items WHERE expenseId = :id")
    suspend fun deleteLineItemsFor(id: Long)

    @Transaction
    suspend fun replaceLineItems(expenseId: Long, items: List<LineItemEntity>) {
        deleteLineItemsFor(expenseId)
        if (items.isNotEmpty()) insertLineItems(items)
    }
}

data class CategoryTotal(val categoryId: Long, val total: Double)
data class DailyTotal(val day: java.time.LocalDate, val total: Double)
