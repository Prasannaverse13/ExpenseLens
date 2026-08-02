package com.expenselens.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface CategoryDao {
    @Query("SELECT * FROM categories ORDER BY id ASC")
    fun observeAll(): Flow<List<CategoryEntity>>

    @Query("SELECT * FROM categories ORDER BY id ASC")
    suspend fun getAll(): List<CategoryEntity>

    @Query("SELECT * FROM categories WHERE id = :id")
    suspend fun byId(id: Long): CategoryEntity?

    @Query("SELECT * FROM categories WHERE name = :name LIMIT 1")
    suspend fun byName(name: String): CategoryEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(items: List<CategoryEntity>): List<Long>

    @Query("DELETE FROM categories")
    suspend fun deleteAll()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun replaceAll(items: List<CategoryEntity>): List<Long>
}
